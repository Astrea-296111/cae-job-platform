package com.example.cae.messaging;

import com.example.cae.common.JsonCodec;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.job.submission.DispatchOutcome;
import com.example.cae.job.submission.ExecutionDispatchService;
import com.example.cae.job.submission.JobAllocationService;
import com.example.cae.messaging.InboxRepository.Claim;
import com.example.cae.messaging.InboxRepository.FailureDisposition;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Only asynchronous Agent acceptance is awaited. The Solver process is never awaited here. */
@Component
public class SubmitCommandListener {
  public static final String CONSUMER = "job-submit-v1";
  private static final Logger LOG = LoggerFactory.getLogger(SubmitCommandListener.class);
  private final io.micrometer.core.instrument.Counter consumeFailures;
  private final JsonCodec json;
  private final InboxRepository inbox;
  private final JobAllocationService allocation;
  private final ExecutionDispatchService dispatch;
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final int maxAttempts;
  private final Duration retryBase;
  private final Counter acked;
  private final Counter retries;
  private final Counter dead;
  private final Timer acceptance;
  private final AtomicInteger infrastructureFailures = new AtomicInteger();

  public SubmitCommandListener(
      JsonCodec json,
      InboxRepository inbox,
      JobAllocationService allocation,
      ExecutionDispatchService dispatch,
      JobRepository jobs,
      ExecutionRepository executions,
      MeterRegistry metrics,
      @Value("${cae.mq.max-delivery-attempts:4}") int maxAttempts,
      @Value("${cae.mq.retry-base-ms:500}") long retryBaseMs) {
    this.json = json;
    this.inbox = inbox;
    this.allocation = allocation;
    this.dispatch = dispatch;
    this.jobs = jobs;
    this.executions = executions;
    this.maxAttempts = maxAttempts;
    this.retryBase = Duration.ofMillis(retryBaseMs);
    if (maxAttempts < 1 || maxAttempts > 100 || retryBaseMs < 1)
      throw new IllegalArgumentException("Submit retry configuration is invalid");
    consumeFailures = metrics.counter("cae.mq.consume.failure");
    acked = metrics.counter("cae.mq.commands.acked");
    retries = metrics.counter("cae.mq.commands.retry");
    dead = metrics.counter("cae.mq.commands.dead");
    acceptance = metrics.timer("cae.mq.command.acceptance");
  }

  @RabbitListener(
      queues = RabbitTopology.SUBMIT_QUEUE,
      ackMode = "MANUAL",
      autoStartup = "${cae.mq.listeners-enabled:true}",
      concurrency = "${cae.mq.submit-concurrency:2}")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    CommandEnvelope envelope;
    try {
      envelope =
          CommandEnvelope.from(json.readMap(new String(message.getBody(), StandardCharsets.UTF_8)));
      validate(envelope);
    } catch (DataAccessException databaseUnavailable) {
      recoverPersistence(channel, tag);
      return;
    } catch (RuntimeException invalid) {
      LOG.warn("Rejected invalid submit envelope reason={}", invalid.getClass().getSimpleName());
      consumeFailures.increment();
      dead.increment();
      channel.basicReject(tag, false);
      return;
    }
    try {
      Claim claim = inbox.claim(envelope, CONSUMER, Duration.ofSeconds(45));
      switch (claim.disposition()) {
        case DONE -> acknowledge(channel, tag);
        case DEAD -> {
          consumeFailures.increment();
          dead.increment();
          channel.basicReject(tag, false);
        }
        case BUSY -> {
          inbox.defer(envelope, CONSUMER);
          acknowledge(channel, tag);
        }
        case ACQUIRED -> handle(envelope, claim, channel, tag);
      }
    } catch (DataAccessException | InboxRepository.LostLeaseException failure) {
      LOG.warn(
          "Submit delivery awaits durable database acceptance eventId={} reason={}",
          envelope.eventId(),
          failure.getClass().getSimpleName());
      recoverPersistence(channel, tag);
    } catch (IllegalArgumentException invalidIdentity) {
      consumeFailures.increment();
      dead.increment();
      channel.basicReject(tag, false);
    }
  }

  private void handle(CommandEnvelope envelope, Claim claim, Channel channel, long tag)
      throws IOException {
    RuntimeException failure = null;
    long started = System.nanoTime();
    try {
      if (envelope.eventType().equals("JOB_SUBMIT_REQUESTED"))
        allocation.allocate(envelope.jobId());
      else if (dispatch.submit(executionId(envelope)) == DispatchOutcome.RETRYABLE_UNCERTAIN)
        throw new RetryableCommandException("Agent acceptance remains uncertain");
    } catch (RuntimeException businessFailure) {
      failure = businessFailure;
    } finally {
      acceptance.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
    if (failure == null) {
      inbox.complete(envelope, CONSUMER, claim.owner());
      acknowledge(channel, tag);
      return;
    }
    String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
    FailureDisposition disposition =
        inbox.fail(
            envelope,
            CONSUMER,
            claim.owner(),
            reason,
            maxAttempts,
            retryBase,
            () -> {
              if (envelope.eventType().equals("EXECUTION_SUBMIT_REQUESTED"))
                dispatch.deliveryExhausted(
                    executionId(envelope), envelope.eventId(), maxAttempts, reason);
            });
    if (disposition == FailureDisposition.RETRY) {
      consumeFailures.increment();
      retries.increment();
      acknowledge(channel, tag);
    } else {
      consumeFailures.increment();
      dead.increment();
      channel.basicReject(tag, false);
    }
  }

  private void validate(CommandEnvelope envelope) {
    if (!envelope.eventType().equals("JOB_SUBMIT_REQUESTED")
        && !envelope.eventType().equals("EXECUTION_SUBMIT_REQUESTED"))
      throw new IllegalArgumentException("Command is not a submit command");
    Map<String, Object> job = jobs.findById(envelope.jobId());
    if (!envelope.jobNo().equals(job.get("jobNo")))
      throw new IllegalArgumentException("Command Job identity mismatch");
    if (envelope.eventType().equals("EXECUTION_SUBMIT_REQUESTED")) {
      Map<String, Object> execution = executions.find(executionId(envelope));
      if (((Number) execution.get("jobId")).longValue() != envelope.jobId())
        throw new IllegalArgumentException("Command execution belongs to another Job");
    }
  }

  private static long executionId(CommandEnvelope envelope) {
    if (!(envelope.payload().get("executionId") instanceof Number id)
        || id.longValue() < 1
        || id.doubleValue() != id.longValue())
      throw new IllegalArgumentException("Missing positive executionId in submit command");
    return id.longValue();
  }

  private void acknowledge(Channel channel, long tag) throws IOException {
    channel.basicAck(tag, false);
    infrastructureFailures.set(0);
    acked.increment();
  }

  private void recoverPersistence(Channel channel, long tag) throws IOException {
    int consecutive = infrastructureFailures.updateAndGet(value -> Math.min(value + 1, 16));
    long delayMs = Math.min(5000, 250L << (consecutive - 1));
    // The broker retains the unacked message throughout recovery, including an abrupt shutdown.
    try {
      TimeUnit.MILLISECONDS.sleep(delayMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    channel.basicNack(tag, false, true);
  }

  private static final class RetryableCommandException extends RuntimeException {
    RetryableCommandException(String message) {
      super(message);
    }
  }
}
