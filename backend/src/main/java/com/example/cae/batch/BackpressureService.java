package com.example.cae.batch;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BackpressureService {
  private final JdbcTemplate jdbc;
  private final int pendingLimit, activeLimit;

  public BackpressureService(
      JdbcTemplate jdbc,
      @Value("${cae.batch.pending-limit:64}") int pendingLimit,
      @Value("${cae.batch.active-limit:32}") int activeLimit) {
    this.jdbc = jdbc;
    this.pendingLimit = pendingLimit;
    this.activeLimit = activeLimit;
    if (pendingLimit < 1 || activeLimit < 1)
      throw new IllegalArgumentException("Invalid backpressure limits");
  }

  public Map<String, Object> snapshot() {
    int outbox =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cae_outbox_event WHERE status IN ('NEW','RETRY','PUBLISHING')",
            Integer.class);
    int active =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cae_job WHERE status IN ('CREATED','VALIDATING','READY','QUEUED','WAITING_RESOURCE','SUBMITTING','SUBMITTED','RUNNING','UNKNOWN','CANCEL_REQUESTED','POST_PROCESSING','REPORTING') AND (batch_id IS NOT NULL OR status NOT IN ('CREATED','READY'))",
            Integer.class);
    return Map.of(
        "outboxPending",
        outbox,
        "activeJobs",
        active,
        "pendingLimit",
        pendingLimit,
        "activeLimit",
        activeLimit,
        "paused",
        outbox >= pendingLimit || active >= activeLimit);
  }

  public boolean paused() {
    return Boolean.TRUE.equals(snapshot().get("paused"));
  }
}
