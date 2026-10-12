package com.example.cae.job.repository;

import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CreateRequestRepository {
  private final JdbcTemplate jdbc;

  public CreateRequestRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Long claim(String actor, String key, String fingerprint) {
    if (!key.matches("[A-Za-z0-9_-]{1,100}"))
      throw ApiException.invalid("Invalid create idempotency key");
    jdbc.update(
        "INSERT INTO cae_job_create_request(actor,idempotency_key,request_fingerprint) VALUES(?,?,?) ON DUPLICATE KEY UPDATE actor=actor",
        actor,
        key,
        fingerprint);
    Map<String, Object> row =
        jdbc.query(
                "SELECT * FROM cae_job_create_request WHERE actor=? AND idempotency_key=? FOR UPDATE",
                SqlRows.MAPPER,
                actor,
                key)
            .get(0);
    if (!fingerprint.equals(row.get("requestFingerprint")))
      throw ApiException.conflict("Create idempotency key belongs to different input");
    return row.get("jobId") == null ? null : ((Number) row.get("jobId")).longValue();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(String actor, String key, long job) {
    jdbc.update(
        "UPDATE cae_job_create_request SET job_id=? WHERE actor=? AND idempotency_key=? AND job_id IS NULL",
        job,
        actor,
        key);
  }
}
