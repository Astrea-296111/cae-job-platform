package com.example.cae.result;

import com.example.cae.common.JsonCodec;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.messaging.CommandEnvelope;
import com.example.cae.messaging.InboxRepository;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** The real parse queue is ACKed only after the durable inbox and parser writes commit. */
@Component
public class ParseCommandListener {
  public static final String CONSUMER = "result-parse-v1";
  public static final String QUEUE = "cae.job.parse";
  private static final Logger LOG = LoggerFactory.getLogger(ParseCommandListener.class);
  private final JsonCodec json;
  private final InboxRepository inbox;
  private final ResultParsingService parsing;
  private final ResultRepository results;
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final int maxAttempts;
  private final Duration retryBase;
  private final Counter acked;
  private final Counter retries;
  private final Counter dead;
  private final io.micrometer.core.instrument.Timer timer;
  private final AtomicInteger databaseFailures = new AtomicInteger();

  public ParseCommandListener(
      JsonCodec json,
      InboxRepository inbox,
      ResultParsingService parsing,
      ResultRepository results,
      JobRepository jobs,
      ExecutionRepository executions,
      MeterRegistry metrics,
      @Value("${cae.mq.max-delivery-attempts:4}") int maxAttempts,
      @Value("${cae.mq.retry-base-ms:500}") long retryBaseMs) {
    if (maxAttempts < 1 || maxAttempts > 100 || retryBaseMs < 1)
      throw new IllegalArgumentException("Invalid parser delivery retry policy");
    this.json = json;
    this.inbox = inbox;
    this.parsing = parsing;
    this.results = results;
    this.jobs = jobs;
    this.executions = executions;
    this.maxAttempts = maxAttempts;
    retryBase = Duration.ofMillis(retryBaseMs);
    acked = metrics.counter("cae.mq.parse.acked");
    retries = metrics.counter("cae.mq.parse.retry");
    dead = metrics.counter("cae.mq.parse.dead");
    timer = metrics.timer("cae.result.parse.duration");
  }

  @RabbitListener(
      queues = QUEUE,
      ackMode = "MANUAL",
      autoStartup = "${cae.mq.listeners-enabled:true}",
      concurrency = "${cae.mq.parse-concurrency:2}")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    CommandEnvelope envelope;
    try {
      envelope =
          CommandEnvelope.from(json.readMap(new String(message.getBody(), StandardCharsets.UTF_8)));
      validate(envelope);
    } catch (DataAccessException unavailable) {
      recover(channel, tag);
      return;
    } catch (RuntimeException malformed) {
      dead.increment();
      channel.basicReject(tag, false);
      return;
    }
    try {
      var claim = inbox.claim(envelope, CONSUMER, Duration.ofSeconds(60));
      switch (claim.disposition()) {
        case DONE -> acknowledge(channel, tag);
        case DEAD -> {
          dead.increment();
          channel.basicReject(tag, false);
        }
        case BUSY -> {
          inbox.defer(envelope, CONSUMER);
          acknowledge(channel, tag);
        }
        case ACQUIRED -> handle(envelope, claim.owner(), channel, tag);
      }
    } catch (DataAccessException | InboxRepository.LostLeaseException unavailable) {
      LOG.warn(
          "Parse command awaits database recovery eventId={} reason={}",
          envelope.eventId(),
          unavailable.getClass().getSimpleName());
      recover(channel, tag);
    } catch (IllegalArgumentException invalidIdentity) {
      dead.increment();
      channel.basicReject(tag, false);
    }
  }

  private void handle(CommandEnvelope command, String owner, Channel channel, long tag)
      throws IOException {
    RuntimeException failure = null;
    long started = System.nanoTime();
    try {
      parsing.parse(command.jobId(), executionId(command), version(command));
    } catch (DataIntegrityViolationException invalidStoredResult) {
      // A deterministic schema violation is a bounded business failure, not a connection outage.
      failure = invalidStoredResult;
    } catch (DataAccessException unavailable) {
      throw unavailable;
    } catch (RuntimeException businessFailure) {
      failure = businessFailure;
    } finally {
      timer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
    if (failure == null) {
      inbox.complete(command, CONSUMER, owner);
      acknowledge(channel, tag);
      return;
    }
    String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
    var disposition =
        inbox.fail(command, CONSUMER, owner, reason, maxAttempts, retryBase, () -> {});
    if (disposition == InboxRepository.FailureDisposition.RETRY) {
      retries.increment();
      acknowledge(channel, tag);
    } else {
      dead.increment();
      channel.basicReject(tag, false);
    }
  }

  private void validate(CommandEnvelope command) {
    if (!command.eventType().equals("RESULT_PARSE_REQUESTED"))
      throw new IllegalArgumentException("Message is not a parse command");
    String version = version(command);
    Map<String, Object> job = jobs.findById(command.jobId());
    Map<String, Object> execution = executions.find(executionId(command));
    if (!command.jobNo().equals(job.get("jobNo"))
        || ((Number) execution.get("jobId")).longValue() != command.jobId()
        || !execution.get("submitRequestId").equals(command.payload().get("executionRequestId")))
      throw new IllegalArgumentException("Parse command Job or execution identity mismatch");
    if (command.payload().containsKey("parseRecordId")) {
      long parseId = positive(command.payload().get("parseRecordId"), "parseRecordId");
      Map<String, Object> record =
          results
              .find(command.jobId(), version)
              .orElseThrow(() -> new IllegalArgumentException("Requested parse record is missing"));
      if (((Number) record.get("id")).longValue() != parseId
          || ((Number) record.get("executionId")).longValue() != executionId(command))
        throw new IllegalArgumentException("Parse record identity mismatch");
    }
  }

  private static String version(CommandEnvelope command) {
    Object requested = command.payload().getOrDefault("parserVersion", "v1");
    if (!(requested instanceof String version))
      throw new IllegalArgumentException("Invalid parser version");
    ResultParserRegistry.validateVersion(version);
    return version;
  }

  private static long executionId(CommandEnvelope command) {
    return positive(command.payload().get("executionId"), "executionId");
  }

  private static long positive(Object value, String name) {
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.longValue() < 1
        || n.doubleValue() != n.longValue())
      throw new IllegalArgumentException("Expected positive integral " + name);
    return n.longValue();
  }

  private void acknowledge(Channel channel, long tag) throws IOException {
    channel.basicAck(tag, false);
    acked.increment();
    databaseFailures.set(0);
  }

  private void recover(Channel channel, long tag) throws IOException {
    int failures = databaseFailures.updateAndGet(count -> Math.min(count + 1, 16));
    try {
      TimeUnit.MILLISECONDS.sleep(Math.min(5000, 250L << (failures - 1)));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    channel.basicNack(tag, false, true);
  }
}
