package com.example.cae.messaging;

import com.example.cae.common.SqlRows;
import com.example.cae.outbox.OutboxRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable handling lease; a transport redelivery never creates a new logical business attempt. */
@Repository
public class InboxRepository {
  private final JdbcTemplate jdbc;
  private final OutboxRepository outbox;

  public InboxRepository(JdbcTemplate jdbc, OutboxRepository outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
  }

  @Transactional
  public Claim claim(CommandEnvelope message, String consumer, Duration lease) {
    validateConsumer(consumer);
    if (lease.isNegative() || lease.isZero())
      throw new IllegalArgumentException("Inbox lease must be positive");
    // The duplicate branch obtains an exclusive unique-row lock directly. INSERT IGNORE followed
    // by FOR UPDATE would let concurrent duplicates deadlock while upgrading shared locks.
    jdbc.update(
        "INSERT INTO cae_message_inbox(logical_event_id,consumer_name,job_id,last_delivery_id) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
        message.eventId(),
        consumer,
        message.jobId(),
        message.deliveryId());
    Map<String, Object> row = locked(message, consumer);
    if (((Number) row.get("jobId")).longValue() != message.jobId())
      throw new IllegalArgumentException("Logical event ID was reused for another Job");
    int failures = ((Number) row.get("retryCount")).intValue();
    String status = row.get("status").toString();
    if (status.equals("DONE")) return new Claim(Disposition.DONE, null, null, failures);
    if (status.equals("DEAD")) return new Claim(Disposition.DEAD, null, null, failures);
    Instant now = Instant.now();
    Instant claimUntil = instant(row.get("claimUntil"));
    Instant retryAt = instant(row.get("nextRetryTime"));
    if (status.equals("PROCESSING") && claimUntil != null && claimUntil.isAfter(now))
      return new Claim(Disposition.BUSY, null, claimUntil.plusMillis(100), failures);
    if (status.equals("RETRY") && retryAt != null && retryAt.isAfter(now))
      return new Claim(Disposition.BUSY, null, retryAt, failures);
    String owner = UUID.randomUUID().toString();
    jdbc.update(
        "UPDATE cae_message_inbox SET status='PROCESSING',claim_owner=?,claim_until=?,next_retry_time=NULL,last_delivery_id=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
        owner,
        Timestamp.from(now.plus(lease)),
        message.deliveryId(),
        SqlRows.id(row));
    return new Claim(Disposition.ACQUIRED, owner, null, failures);
  }

  @Transactional
  public void complete(CommandEnvelope message, String consumer, String owner) {
    if (jdbc.update(
            "UPDATE cae_message_inbox SET status='DONE',claim_owner=NULL,claim_until=NULL,next_retry_time=NULL,last_error=NULL,completed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE logical_event_id=? AND consumer_name=? AND status='PROCESSING' AND claim_owner=?",
            message.eventId(),
            consumer,
            owner)
        != 1) throw new LostLeaseException();
  }

  /** A retry Outbox row and release of the inbox lease commit before ACK of the old delivery. */
  @Transactional
  public FailureDisposition fail(
      CommandEnvelope message,
      String consumer,
      String owner,
      String reason,
      int maxAttempts,
      Duration base,
      Runnable onExhaustion) {
    if (maxAttempts < 1 || maxAttempts > 100 || base.isNegative() || base.isZero())
      throw new IllegalArgumentException("Message retry policy is invalid");
    Map<String, Object> row = locked(message, consumer);
    if (!"PROCESSING".equals(row.get("status")) || !owner.equals(row.get("claimOwner")))
      throw new LostLeaseException();
    int failures = ((Number) row.get("retryCount")).intValue() + 1;
    boolean dead = failures >= maxAttempts;
    Instant retryAt = Instant.now().plus(OutboxRepository.backoff(base, failures));
    if (dead) onExhaustion.run();
    else outbox.appendRetry(message, failures, retryAt, reason);
    jdbc.update(
        "UPDATE cae_message_inbox SET status=?,retry_count=?,claim_owner=NULL,claim_until=NULL,next_retry_time=?,last_error=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
        dead ? "DEAD" : "RETRY",
        failures,
        dead ? null : Timestamp.from(retryAt),
        OutboxRepository.bounded(reason),
        SqlRows.id(row));
    return dead ? FailureDisposition.DEAD : FailureDisposition.RETRY;
  }

  /** A concurrent delivery is durably deferred; it is neither dropped nor immediately requeued. */
  @Transactional
  public void await(CommandEnvelope message, String consumer, String owner, Duration delay) {
    Map<String, Object> row = locked(message, consumer);
    if (!"PROCESSING".equals(row.get("status")) || !owner.equals(row.get("claimOwner")))
      throw new LostLeaseException();
    Instant retryAt = Instant.now().plus(delay);
    outbox.appendRetry(
        message,
        ((Number) row.get("retryCount")).intValue(),
        retryAt,
        "Awaiting the existing stage lease");
    jdbc.update(
        "UPDATE cae_message_inbox SET status='RETRY',claim_owner=NULL,claim_until=NULL,next_retry_time=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
        Timestamp.from(retryAt),
        SqlRows.id(row));
  }

  /** A concurrent delivery is durably deferred; it is neither dropped nor immediately requeued. */
  @Transactional
  public void defer(CommandEnvelope message, String consumer) {
    Map<String, Object> row = locked(message, consumer);
    if (List.of("DONE", "DEAD").contains(row.get("status"))) return;
    Instant retryAt = instant(row.get("nextRetryTime"));
    Instant leaseUntil = instant(row.get("claimUntil"));
    Instant now = Instant.now();
    Instant availableAt = now.plusMillis(100);
    if (retryAt != null && retryAt.isAfter(availableAt)) availableAt = retryAt;
    if (leaseUntil != null && leaseUntil.isAfter(availableAt))
      availableAt = leaseUntil.plusMillis(100);
    outbox.appendRetry(
        message,
        ((Number) row.get("retryCount")).intValue(),
        availableAt,
        "Logical command is already being handled or awaits its retry time");
  }

  private Map<String, Object> locked(CommandEnvelope message, String consumer) {
    return jdbc
        .query(
            "SELECT * FROM cae_message_inbox WHERE logical_event_id=? AND consumer_name=? FOR UPDATE",
            SqlRows.MAPPER,
            message.eventId(),
            consumer)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Inbox claim is missing"));
  }

  private static Instant instant(Object value) {
    return value == null ? null : Instant.parse(value.toString());
  }

  private static void validateConsumer(String consumer) {
    if (consumer == null || !consumer.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid consumer identity");
  }

  public enum Disposition {
    ACQUIRED,
    DONE,
    DEAD,
    BUSY
  }

  public enum FailureDisposition {
    RETRY,
    DEAD
  }

  public record Claim(Disposition disposition, String owner, Instant deferUntil, int failures) {}

  public static final class LostLeaseException extends RuntimeException {
    public LostLeaseException() {
      super("Inbox handling lease was superseded");
    }
  }
}
