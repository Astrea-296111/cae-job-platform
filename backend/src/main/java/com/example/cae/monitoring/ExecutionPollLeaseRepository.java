package com.example.cae.monitoring;

import com.example.cae.common.SqlRows;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Poll ownership only limits duplicate work; business CAS and identity checks remain authoritative.
 */
@Repository
public class ExecutionPollLeaseRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public ExecutionPollLeaseRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  public List<Long> candidates(int limit) {
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("Poll batch must be 1..200");
    return jdbc.queryForList(
        "SELECT e.id FROM cae_job_execution e JOIN cae_job j ON j.id=e.job_id"
            + " LEFT JOIN cae_resource_reservation r ON r.execution_id=e.id"
            + " LEFT JOIN cae_execution_poll_lease p ON p.execution_id=e.id"
            + " WHERE ((j.current_execution_id=e.id AND (j.status IN"
            + " ('SUBMITTING','SUBMITTED','RUNNING','UNKNOWN','CANCEL_REQUESTED')"
            + " OR (j.status='POST_PROCESSING' AND e.status='SUCCESS'"
            + " AND e.result_parse_requested_at IS NULL)))"
            + " OR (e.status IN ('SUCCESS','RUNTIME_FAILED','TIMEOUT','CANCELLED','SUBMIT_FAILED')"
            + " AND r.status IN ('RESERVED','IN_USE')))"
            + " AND (p.next_poll_at IS NULL OR p.next_poll_at<=UTC_TIMESTAMP(6))"
            + " AND (p.lease_until IS NULL OR p.lease_until<=UTC_TIMESTAMP(6))"
            + " ORDER BY COALESCE(p.next_poll_at,e.created_at),e.id LIMIT ?",
        Long.class,
        limit);
  }

  /** Each initialization commits before the lease-only claim transaction begins. */
  public void ensure(List<Long> executionIds) {
    checkedIds(executionIds);
    for (long id : executionIds.stream().distinct().sorted().toList()) {
      long jobId =
          jdbc.queryForObject("SELECT job_id FROM cae_job_execution WHERE id=?", Long.class, id);
      jdbc.update(
          "INSERT INTO cae_execution_poll_lease(execution_id,job_id) VALUES(?,?)"
              + " ON DUPLICATE KEY UPDATE execution_id=execution_id",
          id,
          jobId);
    }
  }

  public List<PollClaim> claim(
      String owner, int limit, Duration lease, List<Long> scopeExecutionIds) {
    if (owner == null
        || !owner.matches("[A-Za-z0-9_-]{1,64}")
        || limit < 1
        || limit > 200
        || lease.isNegative()
        || lease.isZero()) throw new IllegalArgumentException("Poll lease is invalid");
    List<Long> ids =
        scopeExecutionIds.isEmpty() ? candidates(limit) : List.copyOf(scopeExecutionIds);
    checkedIds(ids);
    if (ids.isEmpty()) return List.of();
    ensure(ids);
    return tx.execute(
        status -> {
          List<Object> parameters = new ArrayList<>(ids);
          parameters.add(limit);
          List<Map<String, Object>> rows =
              jdbc.query(
                  "SELECT * FROM cae_execution_poll_lease WHERE execution_id IN ("
                      + String.join(",", ids.stream().map(id -> "?").toList())
                      + ")"
                      + " AND next_poll_at<=UTC_TIMESTAMP(6)"
                      + " AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6))"
                      + " ORDER BY next_poll_at,execution_id LIMIT ? FOR UPDATE SKIP LOCKED",
                  SqlRows.MAPPER,
                  parameters.toArray());
          List<PollClaim> claims = new ArrayList<>();
          for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("executionId")).longValue();
            long generation = ((Number) row.get("generation")).longValue() + 1;
            jdbc.update(
                "UPDATE cae_execution_poll_lease SET owner_token=?,generation=?,lease_until=?"
                    + " WHERE execution_id=?",
                owner,
                generation,
                Timestamp.from(Instant.now().plus(lease)),
                id);
            claims.add(
                new PollClaim(id, ((Number) row.get("jobId")).longValue(), owner, generation));
          }
          return claims;
        });
  }

  /** Called after locking Job and Execution, never before those business rows. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean owns(PollClaim claim) {
    return !jdbc.queryForList(
            "SELECT execution_id FROM cae_execution_poll_lease WHERE execution_id=?"
                + " AND owner_token=? AND generation=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",
            Long.class,
            claim.executionId(),
            claim.owner(),
            claim.generation())
        .isEmpty();
  }

  public boolean finish(PollClaim claim, Duration delay, String failure) {
    if (delay.isNegative()) throw new IllegalArgumentException("Poll delay cannot be negative");
    String bounded =
        failure == null ? null : failure.substring(0, Math.min(1000, failure.length()));
    return jdbc.update(
            "UPDATE cae_execution_poll_lease SET owner_token=NULL,lease_until=NULL,next_poll_at=?,"
                + " failure_count=CASE WHEN ? IS NULL THEN 0 ELSE failure_count+1 END,"
                + " last_observed_at=CASE WHEN ? IS NULL THEN UTC_TIMESTAMP(6) ELSE last_observed_at END,"
                + " last_error=? WHERE execution_id=? AND owner_token=? AND generation=?"
                + " AND lease_until>UTC_TIMESTAMP(6)",
            Timestamp.from(Instant.now().plus(delay)),
            bounded,
            bounded,
            bounded,
            claim.executionId(),
            claim.owner(),
            claim.generation())
        == 1;
  }

  public void makeDue(long executionId) {
    ensure(List.of(executionId));
    jdbc.update(
        "UPDATE cae_execution_poll_lease SET next_poll_at=UTC_TIMESTAMP(6)"
            + " WHERE execution_id=? AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6))",
        executionId);
  }

  private static void checkedIds(List<Long> ids) {
    if (ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id <= 0))
      throw new IllegalArgumentException("Execution IDs must be a bounded positive list");
  }

  public record PollClaim(long executionId, long jobId, String owner, long generation) {}
}
