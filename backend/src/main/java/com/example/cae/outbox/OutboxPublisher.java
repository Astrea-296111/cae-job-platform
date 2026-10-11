package com.example.cae.outbox;

import com.example.cae.common.JsonCodec;
import com.example.cae.outbox.OutboxRepository.ClaimedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
  private final OutboxRepository outbox;
  private final RabbitTemplate rabbit;
  private final JsonCodec json;
  private final boolean background;
  private final int batchSize;
  private final int maxRetries;
  private final long confirmTimeoutMs;
  private final Counter published;
  private final Counter retried;
  private final AtomicLong pending = new AtomicLong();

  public OutboxPublisher(
      OutboxRepository outbox,
      RabbitTemplate rabbit,
      JsonCodec json,
      MeterRegistry metrics,
      @Value("${cae.background.enabled:true}") boolean background,
      @Value("${cae.outbox.batch-size:20}") int batchSize,
      @Value("${cae.outbox.max-publish-attempts:8}") int maxRetries,
      @Value("${cae.outbox.confirm-timeout-ms:3000}") long confirmTimeoutMs) {
    this.outbox = outbox;
    this.rabbit = rabbit;
    this.json = json;
    this.background = background;
    this.batchSize = batchSize;
    this.maxRetries = maxRetries;
    this.confirmTimeoutMs = confirmTimeoutMs;
    if (batchSize < 1 || batchSize > 200 || maxRetries < 1 || confirmTimeoutMs < 1)
      throw new IllegalArgumentException("Outbox publisher configuration is invalid");
    rabbit.setMandatory(true);
    published = metrics.counter("cae.outbox.published");
    retried = metrics.counter("cae.outbox.publish.retry");
    Gauge.builder("cae.outbox.pending", pending, AtomicLong::get).register(metrics);
  }

  @Scheduled(fixedDelayString = "${cae.outbox.scan-ms:500}")
  public void scan() {
    if (background) publishBatch();
  }

  public int publishBatch() {
    return publish(outbox.claim(UUID.randomUUID().toString(), batchSize, lease()));
  }

  public int publishIds(List<Long> ids) {
    return publish(outbox.claimIds(UUID.randomUUID().toString(), ids, lease()));
  }

  private Duration lease() {
    return Duration.ofMillis(batchSize * (confirmTimeoutMs + 3000L) + 30_000);
  }

  private int publish(List<ClaimedEvent> events) {
    int sent = 0;
    for (ClaimedEvent event : events) {
      CorrelationData correlation = new CorrelationData(event.envelope().deliveryId());
      try {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.envelope().deliveryId());
        properties.setType(event.envelope().eventType());
        properties.setTimestamp(Date.from(event.envelope().occurredAt()));
        properties.setHeader("logicalEventId", event.envelope().eventId());
        properties.setHeader("deliveryAttempt", event.envelope().deliveryAttempt());
        Message message =
            new Message(
                json.write(event.envelope().asMap()).getBytes(StandardCharsets.UTF_8), properties);
        rabbit.send(event.exchange(), event.routing(), message, correlation);
        CorrelationData.Confirm confirm =
            correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        if (!confirm.isAck())
          throw new IllegalStateException("Broker negatively acknowledged publication");
        if (correlation.getReturned() != null)
          throw new IllegalStateException("Mandatory publication was returned as unroutable");
        if (outbox.markSent(event)) {
          published.increment();
          sent++;
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        retry(event, "Publisher interrupted before confirmed delivery");
        break;
      } catch (Exception failure) {
        retry(event, failure.getClass().getSimpleName() + ": " + failure.getMessage());
      }
    }
    pending.set(outbox.pendingCount());
    return sent;
  }

  private void retry(ClaimedEvent event, String reason) {
    if (outbox.retryOrDead(event, reason, maxRetries, Duration.ofMillis(500))) retried.increment();
    LOG.warn(
        "Outbox delivery deferred eventId={} deliveryId={} reason={}",
        event.envelope().eventId(),
        event.envelope().deliveryId(),
        OutboxRepository.bounded(reason));
  }
}
