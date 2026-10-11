package com.example.cae.messaging;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** eventId identifies business handling; deliveryId identifies one physical outbox publication. */
public record CommandEnvelope(
    String eventId,
    String deliveryId,
    String eventType,
    long jobId,
    String jobNo,
    Instant occurredAt,
    int payloadVersion,
    int deliveryAttempt,
    Map<String, Object> payload) {

  public CommandEnvelope {
    identifier(eventId);
    identifier(deliveryId);
    if (eventType == null
        || !eventType.matches("[A-Z][A-Z0-9_]{0,63}")
        || jobId <= 0
        || jobNo == null
        || !jobNo.matches("CAE-[0-9]{8}-[0-9]{6,}")
        || occurredAt == null
        || payloadVersion != 1
        || deliveryAttempt < 0
        || deliveryAttempt > 100
        || payload == null)
      throw new IllegalArgumentException("Message violates command envelope version 1");
    payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  public static CommandEnvelope create(
      String type, long jobId, String jobNo, Map<String, Object> payload) {
    String id = "evt-" + UUID.randomUUID();
    return new CommandEnvelope(id, id, type, jobId, jobNo, Instant.now(), 1, 0, payload);
  }

  public CommandEnvelope retry(int attempt) {
    return new CommandEnvelope(
        eventId,
        "evt-" + UUID.randomUUID(),
        eventType,
        jobId,
        jobNo,
        occurredAt,
        payloadVersion,
        attempt,
        payload);
  }

  @SuppressWarnings("unchecked")
  public static CommandEnvelope from(Map<String, Object> values) {
    return new CommandEnvelope(
        string(values, "eventId"),
        string(values, "deliveryId"),
        string(values, "eventType"),
        number(values, "jobId").longValue(),
        string(values, "jobNo"),
        Instant.parse(string(values, "occurredAt")),
        Math.toIntExact(number(values, "payloadVersion").longValue()),
        Math.toIntExact(number(values, "deliveryAttempt").longValue()),
        (Map<String, Object>) values.get("payload"));
  }

  public Map<String, Object> asMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("eventId", eventId);
    values.put("deliveryId", deliveryId);
    values.put("eventType", eventType);
    values.put("jobId", jobId);
    values.put("jobNo", jobNo);
    values.put("occurredAt", occurredAt.toString());
    values.put("payloadVersion", payloadVersion);
    values.put("deliveryAttempt", deliveryAttempt);
    values.put("payload", payload);
    return values;
  }

  private static String string(Map<String, Object> values, String key) {
    if (!(values.get(key) instanceof String value))
      throw new IllegalArgumentException("Missing message field: " + key);
    return value;
  }

  private static Number number(Map<String, Object> values, String key) {
    if (!(values.get(key) instanceof Number value))
      throw new IllegalArgumentException("Missing message field: " + key);
    if (value.doubleValue() != value.longValue())
      throw new IllegalArgumentException("Non-integral message field: " + key);
    return value;
  }

  private static void identifier(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("Invalid message identifier");
  }
}
