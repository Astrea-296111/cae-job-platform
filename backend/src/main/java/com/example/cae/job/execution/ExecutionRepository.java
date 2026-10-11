package com.example.cae.job.execution;

import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExecutionRepository {
  private static final Set<String> PATCH_COLUMNS =
      Set.of(
          "external_execution_id",
          "status",
          "process_identity",
          "pid",
          "exit_code",
          "raw_solver_code",
          "raw_message",
          "progress",
          "cancel_requested",
          "submitted_at",
          "started_at",
          "finished_at");
  private final JdbcTemplate jdbc;
  private final JsonCodec json;

  public ExecutionRepository(JdbcTemplate jdbc, JsonCodec json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Map<String, Object> find(long id) {
    List<Map<String, Object>> rows =
        jdbc.query("SELECT * FROM cae_job_execution WHERE id=?", SqlRows.MAPPER, id);
    if (rows.isEmpty()) throw ApiException.notFound("Execution");
    return decode(rows.get(0));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> locked(long id) {
    List<Map<String, Object>> rows =
        jdbc.query("SELECT * FROM cae_job_execution WHERE id=? FOR UPDATE", SqlRows.MAPPER, id);
    if (rows.isEmpty()) throw ApiException.notFound("Execution");
    return decode(rows.get(0));
  }

  public List<Map<String, Object>> history(long jobId) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT * FROM cae_job_execution WHERE job_id=? ORDER BY attempt_no",
            SqlRows.MAPPER,
            jobId);
    rows.forEach(this::decode);
    return rows;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> create(
      long jobId, String nodeId, long reservationId, Map<String, Object> request) {
    int attempt =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(attempt_no),0)+1 FROM cae_job_execution WHERE job_id=?",
            Integer.class,
            jobId);
    GeneratedKeyHolder key = new GeneratedKeyHolder();
    String requestId = request.get("executionRequestId").toString();
    Object[] values = {
      jobId,
      attempt,
      requestId,
      nodeId,
      reservationId,
      request.get("workspace") + "/executions/" + requestId,
      json.write(request)
    };
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "INSERT INTO cae_job_execution(job_id,attempt_no,submit_request_id,node_id,reservation_id,workspace,request_json) VALUES(?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
          return statement;
        },
        key);
    return find(key.getKey().longValue());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> patch(Map<String, Object> expected, Map<String, Object> fields) {
    if (fields.isEmpty()) return find(SqlRows.id(expected));
    List<String> assignments = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    fields.forEach(
        (name, value) -> {
          String column =
              name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
          if (!PATCH_COLUMNS.contains(column))
            throw new IllegalArgumentException("Unsupported execution field: " + name);
          assignments.add(column + "=?");
          values.add(value);
        });
    values.add(SqlRows.id(expected));
    values.add(expected.get("version"));
    if (jdbc.update(
            "UPDATE cae_job_execution SET "
                + String.join(",", assignments)
                + ",version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=?",
            values.toArray())
        != 1) throw ApiException.conflict("Execution changed concurrently");
    return find(SqlRows.id(expected));
  }

  private Map<String, Object> decode(Map<String, Object> row) {
    row.put("request", json.readMap(row.remove("requestJson")));
    return row;
  }
}
