package com.example.cae.batch;

import com.example.cae.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BatchRepository {
  private final JdbcTemplate jdbc;
  private final JsonCodec json;

  public BatchRepository(JdbcTemplate jdbc, JsonCodec json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Map<String, Object> claim(
      String actor, String key, String fingerprint, String name, long project, int total) {
    jdbc.queryForObject(
        "SELECT id FROM cae_batch_admission_gate WHERE id=1 FOR UPDATE", Integer.class);
    String no = "BATCH-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO cae_batch(batch_no,name,project_id,submitter_id,idempotency_key,request_fingerprint,total) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
        no,
        name,
        project,
        actor,
        key,
        fingerprint,
        total);
    var row =
        jdbc.query(
                "SELECT * FROM cae_batch WHERE submitter_id=? AND idempotency_key=? FOR UPDATE",
                SqlRows.MAPPER,
                actor,
                key)
            .get(0);
    if (!fingerprint.equals(row.get("requestFingerprint")))
      throw ApiException.conflict("Batch idempotency key belongs to different input");
    row.put("newIntent", no.equals(row.get("batchNo")));
    return row;
  }

  public void items(long batch, List<?> requests) {
    // Persist bounded chunks of intent rows; no Job validation or MQ fan-out in this transaction.
    jdbc.batchUpdate(
        "INSERT INTO cae_batch_item(batch_id,item_index,request_json) VALUES(?,?,?)",
        java.util.stream.IntStream.range(0, requests.size()).boxed().toList(),
        25,
        (statement, value) -> {
          statement.setLong(1, batch);
          statement.setInt(2, value);
          statement.setString(3, json.write(requests.get(value)));
        });
  }

  public Map<String, Object> find(String no) {
    return jdbc.query("SELECT * FROM cae_batch WHERE batch_no=?", SqlRows.MAPPER, no).stream()
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Batch"));
  }

  public List<Map<String, Object>> list(String user, boolean admin, Long project) {
    return jdbc.query(
        "SELECT b.* FROM cae_batch b WHERE (? IS NULL OR b.project_id=?) AND (? OR EXISTS(SELECT 1 FROM cae_project_member m WHERE m.project_id=b.project_id AND m.username=?)) ORDER BY b.id DESC LIMIT 100",
        SqlRows.MAPPER,
        project,
        project,
        admin,
        user);
  }

  public List<Map<String, Object>> children(long batch) {
    return jdbc.query(
        "SELECT i.item_index,i.status AS admission_status,i.failure_code AS admission_failure_code,i.failure_message AS admission_failure_message,j.job_no,j.analysis_type,j.condition_code,j.status,j.stage,j.failure_code,j.failure_message,j.current_execution_id,x.node_id,x.pid FROM cae_batch_item i LEFT JOIN cae_job j ON j.id=i.job_id LEFT JOIN cae_job_execution x ON x.id=j.current_execution_id WHERE i.batch_id=? ORDER BY i.item_index",
        SqlRows.MAPPER,
        batch);
  }

  public List<Long> pendingBatches() {
    return jdbc.queryForList(
        "SELECT b.id FROM cae_batch b WHERE EXISTS(SELECT 1 FROM cae_batch_item i WHERE i.batch_id=b.id AND i.status='PENDING') ORDER BY b.updated_at,b.id LIMIT 4",
        Long.class);
  }

  public Optional<Map<String, Object>> lockNext(long batch) {
    jdbc.queryForObject(
        "SELECT id FROM cae_batch_admission_gate WHERE id=1 FOR UPDATE", Integer.class);
    if (jdbc.query(
            "SELECT id FROM cae_batch WHERE id=? FOR UPDATE SKIP LOCKED", SqlRows.MAPPER, batch)
        .isEmpty()) return Optional.empty();
    return jdbc
        .query(
            "SELECT i.*,b.submitter_id,b.project_id FROM cae_batch_item i JOIN cae_batch b ON b.id=i.batch_id WHERE i.batch_id=? AND i.status='PENDING' ORDER BY i.item_index LIMIT 1 FOR UPDATE",
            SqlRows.MAPPER,
            batch)
        .stream()
        .findFirst();
  }

  public void completed(long item, long batch, Long job, String code, String message) {
    jdbc.update(
        "UPDATE cae_batch_item SET status=?,job_id=?,failure_code=?,failure_message=? WHERE id=? AND status='PENDING'",
        job == null ? "REJECTED" : "CREATED",
        job,
        code,
        message,
        item);
    jdbc.update(
        "UPDATE cae_batch SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?", batch);
  }
}
