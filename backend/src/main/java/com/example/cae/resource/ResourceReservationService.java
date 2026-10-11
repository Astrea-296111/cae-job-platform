package com.example.cae.resource;

import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable mock license and resource accounting; all operations lock Job → license → node →
 * reservation.
 */
@Service
public class ResourceReservationService {
  public record Reservation(
      long id, long jobId, String nodeId, int cpu, int memoryGb, String feature, int tokens) {}

  private final JdbcTemplate jdbc;
  private final long freshHeartbeatSeconds;

  public ResourceReservationService(
      JdbcTemplate jdbc, @Value("${cae.nodes.suspect-seconds:10}") long freshHeartbeatSeconds) {
    this.jdbc = jdbc;
    this.freshHeartbeatSeconds = freshHeartbeatSeconds;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Reservation> reserve(
      long jobId, String nodeId, int cpu, int memoryGb, String feature, int tokens) {
    if (cpu < 1 || memoryGb < 1 || tokens < 1)
      throw ApiException.invalid("Reservation requests must be positive");
    Map<String, Object> job = lockJob(jobId);
    List<Map<String, Object>> active =
        jdbc.query(
            "SELECT * FROM cae_resource_reservation WHERE job_id=? AND status IN ('RESERVED','IN_USE')",
            SqlRows.MAPPER,
            jobId);
    if (!active.isEmpty()) {
      Reservation prior = reservation(active.get(0));
      if (!prior.nodeId().equals(nodeId)
          || prior.cpu() != cpu
          || prior.memoryGb() != memoryGb
          || !prior.feature().equals(feature)
          || prior.tokens() != tokens)
        throw ApiException.conflict(
            "Job already has an active reservation with a different request");
      return Optional.of(prior);
    }
    int licensed =
        jdbc.update(
            "UPDATE cae_license_pool SET reserved_tokens=reserved_tokens+? WHERE feature=? AND reserved_tokens+in_use_tokens+?<=total_tokens",
            tokens,
            feature,
            tokens);
    if (licensed == 0) return Optional.empty();
    int reserved =
        jdbc.update(
            "UPDATE cae_compute_node SET cpu_reserved=cpu_reserved+?,memory_reserved_gb=memory_reserved_gb+? WHERE node_id=? AND status='ONLINE' AND drained=FALSE AND workspace_reachable=TRUE AND last_heartbeat>=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) AND JSON_CONTAINS(solver_tags,JSON_QUOTE(?)) AND GREATEST(cpu_used,observed_cpu_used)+cpu_reserved+?<=cpu_total AND GREATEST(memory_used_gb,observed_memory_used_gb)+memory_reserved_gb+?<=memory_total_gb",
            cpu,
            memoryGb,
            nodeId,
            -freshHeartbeatSeconds,
            job.get("analysisType"),
            cpu,
            memoryGb);
    if (reserved == 0) {
      checked(
          jdbc.update(
              "UPDATE cae_license_pool SET reserved_tokens=reserved_tokens-? WHERE feature=? AND reserved_tokens>=?",
              tokens,
              feature,
              tokens));
      return Optional.empty();
    }
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO cae_resource_reservation(job_id,node_id,feature,token_count,cpu,memory_gb,status) VALUES(?,?,?,?,?,?,'RESERVED')",
                  Statement.RETURN_GENERATED_KEYS);
          statement.setLong(1, jobId);
          statement.setString(2, nodeId);
          statement.setString(3, feature);
          statement.setInt(4, tokens);
          statement.setInt(5, cpu);
          statement.setInt(6, memoryGb);
          return statement;
        },
        keys);
    return Optional.of(
        new Reservation(keys.getKey().longValue(), jobId, nodeId, cpu, memoryGb, feature, tokens));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void associateExecution(long reservationId, long executionId) {
    int updated =
        jdbc.update(
            "UPDATE cae_resource_reservation r JOIN cae_job_execution e ON e.id=? AND e.job_id=r.job_id AND e.node_id=r.node_id AND e.reservation_id=r.id SET r.execution_id=? WHERE r.id=? AND r.status='RESERVED' AND r.execution_id IS NULL",
            executionId,
            executionId,
            reservationId);
    if (updated == 0) {
      Long existing =
          jdbc.queryForObject(
              "SELECT execution_id FROM cae_resource_reservation WHERE id=?",
              Long.class,
              reservationId);
      if (existing == null || existing.longValue() != executionId)
        throw ApiException.conflict("Reservation cannot be associated with this execution");
    }
  }

  @Transactional
  public boolean accepted(long reservationId) {
    Map<String, Object> row = lockLedger(reservationId);
    String status = row.get("status").toString();
    if (status.equals("IN_USE") || status.equals("RELEASED")) return false;
    if (!status.equals("RESERVED")) throw new IllegalStateException("Unknown reservation status");
    int tokens = integer(row, "tokenCount"),
        cpu = integer(row, "cpu"),
        memory = integer(row, "memoryGb");
    String feature = row.get("feature").toString(), node = row.get("nodeId").toString();
    checked(
        jdbc.update(
            "UPDATE cae_license_pool SET reserved_tokens=reserved_tokens-?,in_use_tokens=in_use_tokens+? WHERE feature=? AND reserved_tokens>=?",
            tokens,
            tokens,
            feature,
            tokens));
    checked(
        jdbc.update(
            "UPDATE cae_compute_node SET cpu_reserved=cpu_reserved-?,memory_reserved_gb=memory_reserved_gb-?,cpu_used=cpu_used+?,memory_used_gb=memory_used_gb+? WHERE node_id=? AND cpu_reserved>=? AND memory_reserved_gb>=?",
            cpu,
            memory,
            cpu,
            memory,
            node,
            cpu,
            memory));
    checked(
        jdbc.update(
            "UPDATE cae_resource_reservation SET status='IN_USE',accepted_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='RESERVED'",
            reservationId));
    return true;
  }

  @Transactional
  public boolean release(long reservationId) {
    Map<String, Object> row = lockLedger(reservationId);
    String status = row.get("status").toString();
    if (status.equals("RELEASED")) return false;
    boolean reserved = status.equals("RESERVED");
    if (!reserved && !status.equals("IN_USE"))
      throw new IllegalStateException("Unknown reservation status");
    int tokens = integer(row, "tokenCount"),
        cpu = integer(row, "cpu"),
        memory = integer(row, "memoryGb");
    String feature = row.get("feature").toString(), node = row.get("nodeId").toString();
    String tokenColumn = reserved ? "reserved_tokens" : "in_use_tokens";
    checked(
        jdbc.update(
            "UPDATE cae_license_pool SET "
                + tokenColumn
                + "="
                + tokenColumn
                + "-? WHERE feature=? AND "
                + tokenColumn
                + ">=?",
            tokens,
            feature,
            tokens));
    String cpuColumn = reserved ? "cpu_reserved" : "cpu_used",
        memoryColumn = reserved ? "memory_reserved_gb" : "memory_used_gb";
    checked(
        jdbc.update(
            "UPDATE cae_compute_node SET "
                + cpuColumn
                + "="
                + cpuColumn
                + "-?,"
                + memoryColumn
                + "="
                + memoryColumn
                + "-? WHERE node_id=? AND "
                + cpuColumn
                + ">=? AND "
                + memoryColumn
                + ">=?",
            cpu,
            memory,
            node,
            cpu,
            memory));
    checked(
        jdbc.update(
            "UPDATE cae_resource_reservation SET status='RELEASED',released_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status=?",
            reservationId,
            status));
    return true;
  }

  @Transactional
  public boolean releaseByExecution(long executionId) {
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id FROM cae_resource_reservation WHERE execution_id=?",
            Long.class,
            executionId);
    return !ids.isEmpty() && release(ids.get(0));
  }

  public List<Map<String, Object>> licenses() {
    return jdbc.query("SELECT * FROM cae_license_pool ORDER BY feature", SqlRows.MAPPER);
  }

  private Map<String, Object> lockJob(long jobId) {
    List<Map<String, Object>> rows =
        jdbc.query("SELECT * FROM cae_job WHERE id=? FOR UPDATE", SqlRows.MAPPER, jobId);
    if (rows.isEmpty()) throw ApiException.notFound("Job");
    return rows.get(0);
  }

  private Map<String, Object> lockLedger(long id) {
    List<Map<String, Object>> rows =
        jdbc.query("SELECT * FROM cae_resource_reservation WHERE id=?", SqlRows.MAPPER, id);
    if (rows.isEmpty()) throw ApiException.notFound("Reservation");
    Map<String, Object> immutable = rows.get(0);
    lockJob(((Number) immutable.get("jobId")).longValue());
    jdbc.queryForObject(
        "SELECT total_tokens FROM cae_license_pool WHERE feature=? FOR UPDATE",
        Integer.class,
        immutable.get("feature"));
    jdbc.queryForObject(
        "SELECT cpu_total FROM cae_compute_node WHERE node_id=? FOR UPDATE",
        Integer.class,
        immutable.get("nodeId"));
    return jdbc.queryForObject(
        "SELECT * FROM cae_resource_reservation WHERE id=? FOR UPDATE", SqlRows.MAPPER, id);
  }

  private Reservation reservation(Map<String, Object> row) {
    return new Reservation(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("jobId")).longValue(),
        row.get("nodeId").toString(),
        integer(row, "cpu"),
        integer(row, "memoryGb"),
        row.get("feature").toString(),
        integer(row, "tokenCount"));
  }

  private int integer(Map<String, Object> value, String field) {
    return ((Number) value.get(field)).intValue();
  }

  private void checked(int rows) {
    if (rows != 1)
      throw new IllegalStateException(
          "Resource ledger invariants were violated; transaction is rolled back");
  }
}
