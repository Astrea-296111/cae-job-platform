package com.example.cae.outbox;

import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.messaging.CommandEnvelope;
import com.example.cae.messaging.RabbitTopology;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {
  private final JdbcTemplate jdbc;
  private final JsonCodec json;

  public OutboxRepository(JdbcTemplate jdbc, JsonCodec json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Invoked inside the transaction which updated the Job and inserted its journal entry. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void appendJobEvent(
      long jobId,
      String eventType,
      String fromStatus,
      String toStatus,
      Map<String, Object> detail) {
    String jobNo = jobNo(jobId);
    Map<String, Object> eventPayload = new LinkedHashMap<>(detail == null ? Map.of() : detail);
    if (fromStatus != null) eventPayload.put("fromStatus", fromStatus);
    if (toStatus != null) eventPayload.put("toStatus", toStatus);
    append(
        CommandEnvelope.create(eventType, jobId, jobNo, eventPayload),
        RabbitTopology.EVENT_EXCHANGE,
        "job." + eventType,
        Instant.now());
    if (RabbitTopology.isCommand(eventType))
      appendCommand(jobId, eventType, detail == null ? Map.of() : detail);
    if (eventType.equals("EXECUTION_ALLOCATED"))
      appendCommand(jobId, "EXECUTION_SUBMIT_REQUESTED", detail);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String appendCommand(long jobId, String eventType, Map<String, Object> payload) {
    CommandEnvelope command = CommandEnvelope.create(eventType, jobId, jobNo(jobId), payload);
    append(
        command, RabbitTopology.COMMAND_EXCHANGE, RabbitTopology.route(eventType), Instant.now());
    return command.eventId();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String appendRetry(
      CommandEnvelope original, int attempt, Instant availableAt, String reason) {
    CommandEnvelope retry = original.retry(attempt);
    append(
        retry,
        RabbitTopology.COMMAND_EXCHANGE,
        RabbitTopology.route(retry.eventType()),
        availableAt);
    jdbc.update(
        "UPDATE cae_outbox_event SET last_error=? WHERE event_id=?",
        bounded(reason),
        retry.deliveryId());
    return retry.deliveryId();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      CommandEnvelope envelope, String exchange, String routing, Instant availableAt) {
    jdbc.update(
        "INSERT INTO cae_outbox_event(event_id,logical_event_id,aggregate_id,event_type,payload,exchange_name,routing_key,delivery_attempt,next_retry_time) VALUES(?,?,?,?,?,?,?,?,?)",
        envelope.deliveryId(),
        envelope.eventId(),
        envelope.jobId(),
        envelope.eventType(),
        json.write(envelope.asMap()),
        exchange,
        routing,
        envelope.deliveryAttempt(),
        Timestamp.from(availableAt));
  }

  @Transactional
  public List<ClaimedEvent> claim(String owner, int limit, Duration lease) {
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("Outbox batch must be 1..200");
    return claimEligible(owner, limit, lease, List.of());
  }

  /**
   * Scoped publication is useful for administration, benchmarks and tests without touching other
   * aggregates.
   */
  @Transactional
  public List<ClaimedEvent> claimIds(String owner, List<Long> ids, Duration lease) {
    if (ids.isEmpty()) return List.of();
    if (ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id <= 0))
      throw new IllegalArgumentException("Outbox IDs must be a bounded positive list");
    return claimEligible(owner, ids.size(), lease, ids);
  }

  private List<ClaimedEvent> claimEligible(
      String owner, int limit, Duration lease, List<Long> ids) {
    if (owner == null
        || !owner.matches("[A-Za-z0-9_-]{1,64}")
        || lease.isNegative()
        || lease.isZero()) throw new IllegalArgumentException("Outbox lease is invalid");
    String scope =
        ids.isEmpty()
            ? ""
            : " AND id IN (" + String.join(",", ids.stream().map(id -> "?").toList()) + ")";
    List<Object> parameters = new ArrayList<>(ids);
    parameters.add(limit);
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT * FROM cae_outbox_event WHERE ((status IN ('NEW','RETRY') AND next_retry_time<=UTC_TIMESTAMP(6)) OR (status='PUBLISHING' AND claim_until<=UTC_TIMESTAMP(6)))"
                + scope
                + " ORDER BY next_retry_time,id LIMIT ? FOR UPDATE SKIP LOCKED",
            SqlRows.MAPPER,
            parameters.toArray());
    Instant until = Instant.now().plus(lease);
    List<ClaimedEvent> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      long id = SqlRows.id(row);
      jdbc.update(
          "UPDATE cae_outbox_event SET status='PUBLISHING',claim_owner=?,claim_until=? WHERE id=?",
          owner,
          Timestamp.from(until),
          id);
      result.add(
          new ClaimedEvent(
              id,
              owner,
              row.get("exchangeName").toString(),
              row.get("routingKey").toString(),
              CommandEnvelope.from(json.readMap(row.get("payload"))),
              ((Number) row.get("retryCount")).intValue()));
    }
    return result;
  }

  public boolean markSent(ClaimedEvent event) {
    return jdbc.update(
            "UPDATE cae_outbox_event SET status='SENT',published_at=UTC_TIMESTAMP(6),claim_owner=NULL,claim_until=NULL,last_error=NULL WHERE id=? AND status='PUBLISHING' AND claim_owner=?",
            event.id(),
            event.owner())
        == 1;
  }

  @Transactional
  public boolean retryOrDead(ClaimedEvent event, String reason, int maxRetries, Duration base) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT retry_count FROM cae_outbox_event WHERE id=? AND status='PUBLISHING' AND claim_owner=? FOR UPDATE",
            SqlRows.MAPPER,
            event.id(),
            event.owner());
    if (rows.isEmpty()) return false;
    int attempts = ((Number) rows.get(0).get("retryCount")).intValue() + 1;
    boolean dead = attempts >= maxRetries;
    jdbc.update(
        "UPDATE cae_outbox_event SET status=?,retry_count=?,next_retry_time=?,claim_owner=NULL,claim_until=NULL,last_error=? WHERE id=? AND claim_owner=?",
        dead ? "DEAD" : "RETRY",
        attempts,
        Timestamp.from(Instant.now().plus(backoff(base, attempts))),
        bounded(reason),
        event.id(),
        event.owner());
    return true;
  }

  public long pendingCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM cae_outbox_event WHERE status IN ('NEW','RETRY','PUBLISHING')",
        Long.class);
  }

  private String jobNo(long jobId) {
    return jdbc.queryForObject("SELECT job_no FROM cae_job WHERE id=?", String.class, jobId);
  }

  public static Duration backoff(Duration base, int failures) {
    long multiplier = 1L << Math.min(Math.max(failures - 1, 0), 16);
    return Duration.ofMillis(
        Math.min(30_000, Math.multiplyExact(Math.min(base.toMillis(), 30_000), multiplier)));
  }

  public static String bounded(String value) {
    if (value == null) return null;
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  public record ClaimedEvent(
      long id,
      String owner,
      String exchange,
      String routing,
      CommandEnvelope envelope,
      int retryCount) {}
}
