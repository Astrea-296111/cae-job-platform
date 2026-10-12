package com.example.cae.report;

import com.example.cae.common.JsonCodec;
import com.example.cae.messaging.CommandEnvelope;
import com.example.cae.messaging.InboxRepository;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class PipelineCommandListener {
  private final JsonCodec json;
  private final InboxRepository inbox;
  private final PipelineService pipeline;
  private final MeterRegistry metrics;
  private final int attempts;
  private final Duration retryBase;

  public PipelineCommandListener(
      JsonCodec json,
      InboxRepository inbox,
      PipelineService pipeline,
      MeterRegistry metrics,
      @Value("${cae.mq.max-delivery-attempts:4}") int attempts,
      @Value("${cae.mq.retry-base-ms:500}") long retryMs) {
    this.json = json;
    this.inbox = inbox;
    this.pipeline = pipeline;
    this.metrics = metrics;
    this.attempts = attempts;
    retryBase = Duration.ofMillis(retryMs);
  }

  @RabbitListener(
      queues = "cae.job.postprocess",
      ackMode = "MANUAL",
      autoStartup = "${cae.mq.listeners-enabled:true}",
      concurrency = "2")
  public void post(Message message, Channel channel) throws IOException {
    consume(message, channel, "POST");
  }

  @RabbitListener(
      queues = "cae.job.report",
      ackMode = "MANUAL",
      autoStartup = "${cae.mq.listeners-enabled:true}",
      concurrency = "2")
  public void report(Message message, Channel channel) throws IOException {
    consume(message, channel, "REPORT");
  }

  private void consume(Message message, Channel channel, String kind) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    CommandEnvelope command;
    try {
      command =
          CommandEnvelope.from(json.readMap(new String(message.getBody(), StandardCharsets.UTF_8)));
      if (!command
          .eventType()
          .equals(kind.equals("POST") ? "POST_PROCESS_REQUESTED" : "REPORT_REQUESTED"))
        throw new IllegalArgumentException("Wrong stage event");
    } catch (RuntimeException malformed) {
      metrics.counter("cae.mq.consume.failure").increment();
      channel.basicReject(tag, false);
      return;
    }
    String consumer = kind.equals("POST") ? "post-process-v1" : "draft-report-v1";
    try {
      var claim = inbox.claim(command, consumer, Duration.ofSeconds(60));
      switch (claim.disposition()) {
        case DONE -> ack(channel, tag, kind);
        case DEAD -> channel.basicReject(tag, false);
        case BUSY -> {
          inbox.defer(command, consumer);
          ack(channel, tag, kind);
        }
        case ACQUIRED -> {
          RuntimeException failure = null;
          try {
            pipeline.process(command, kind);
          } catch (PipelineService.StageBusyException busy) {
            inbox.await(command, consumer, claim.owner(), Duration.ofSeconds(5));
            ack(channel, tag, kind);
            return;
          } catch (DataIntegrityViolationException constraint) {
            failure = constraint;
          } catch (DataAccessException unavailable) {
            throw unavailable;
          } catch (RuntimeException business) {
            failure = business;
          }
          if (failure == null) {
            inbox.complete(command, consumer, claim.owner());
            ack(channel, tag, kind);
          } else {
            metrics.counter("cae.mq.consume.failure").increment();
            var disposition =
                inbox.fail(
                    command,
                    consumer,
                    claim.owner(),
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    attempts,
                    retryBase,
                    () -> {});
            if (disposition == InboxRepository.FailureDisposition.RETRY) {
              metrics.counter("cae.mq.stage.retry", "stage", kind).increment();
              ack(channel, tag, kind);
            } else {
              metrics.counter("cae.mq.stage.dead", "stage", kind).increment();
              channel.basicReject(tag, false);
            }
          }
        }
      }
    } catch (DataAccessException | InboxRepository.LostLeaseException unavailable) {
      try {
        TimeUnit.MILLISECONDS.sleep(500);
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
      }
      channel.basicNack(tag, false, true);
    } catch (IllegalArgumentException malformed) {
      channel.basicReject(tag, false);
    }
  }

  private void ack(Channel channel, long tag, String kind) throws IOException {
    channel.basicAck(tag, false);
    metrics.counter("cae.mq.stage.acked", "stage", kind).increment();
  }
}
