package com.example.cae.report;

import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PipelineRepository {
  public List<Map<String, Object>> baselines(Map<String, Object> job, Map<String, Object> parse) {
    return jdbc.query(
        "SELECT j.job_no,j.id AS job_id,p.id AS parse_record_id,r.revision AS model_revision,j.created_at FROM cae_result_parse_record p JOIN cae_job j ON j.id=p.job_id JOIN cae_model_revision r ON r.id=j.model_revision_id WHERE j.status='SUCCESS' AND j.id<>? AND j.project_id=? AND r.model_id=(SELECT model_id FROM cae_model_revision WHERE id=?) AND j.analysis_type=? AND j.condition_code=? AND p.status='COMPLETE' AND p.parser_version=? AND p.subcase=? AND p.execution_id=j.current_execution_id ORDER BY j.id DESC LIMIT 100",
        SqlRows.MAPPER,
        SqlRows.id(job),
        job.get("projectId"),
        job.get("modelRevisionId"),
        job.get("analysisType"),
        job.get("conditionCode"),
        parse.get("parserVersion"),
        parse.get("subcase"));
  }

  public List<Map<String, Object>> templates() {
    return jdbc.query("SELECT * FROM cae_report_template ORDER BY code", SqlRows.MAPPER);
  }

  public void template(String code, boolean enabled) {
    if (jdbc.update(
            "UPDATE cae_report_template SET enabled=? WHERE code=? AND version='v1'", enabled, code)
        != 1) throw ApiException.notFound("Report template");
  }

  public boolean templateEnabled(String code) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM cae_report_template WHERE code=? AND version='v1' AND enabled=TRUE",
            Integer.class,
            code)
        == 1;
  }

  public Optional<Map<String, Object>> intent(long job, String key) {
    return jdbc
        .query(
            "SELECT * FROM cae_report_generation_request WHERE job_id=? AND idempotency_key=?",
            SqlRows.MAPPER,
            job,
            key)
        .stream()
        .findFirst();
  }

  public void intent(long job, String key, String fingerprint, long parse, int version) {
    jdbc.update(
        "INSERT INTO cae_report_generation_request(job_id,idempotency_key,request_fingerprint,parse_record_id,stage_version) VALUES(?,?,?,?,?)",
        job,
        key,
        fingerprint,
        parse,
        version);
  }

  private final JdbcTemplate jdbc;
  private final JsonCodec json;
  private final RenderFingerprint fingerprints;

  public PipelineRepository(JdbcTemplate jdbc, JsonCodec json, RenderFingerprint fingerprints) {
    this.jdbc = jdbc;
    this.json = json;
    this.fingerprints = fingerprints;
  }

  public Map<String, Object> parse(long id) {
    return jdbc
        .query("SELECT * FROM cae_result_parse_record WHERE id=?", SqlRows.MAPPER, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Parse record"));
  }

  public List<Map<String, Object>> metrics(long parseId) {
    return jdbc.query(
        "SELECT * FROM result_metric WHERE parse_record_id=? ORDER BY metric_code",
        SqlRows.MAPPER,
        parseId);
  }

  public Map<String, Object> context(long jobId) {
    return jdbc.query(
            "SELECT p.code AS project_code,p.name AS project_name,m.code AS model_code,m.name AS model_name,r.revision AS model_revision FROM cae_job j JOIN cae_project p ON p.id=j.project_id JOIN cae_model_revision r ON r.id=j.model_revision_id JOIN cae_model m ON m.id=r.model_id WHERE j.id=?",
            SqlRows.MAPPER,
            jobId)
        .get(0);
  }

  public Optional<Map<String, Object>> baseline(
      Map<String, Object> job, Map<String, Object> parse, String explicit) {
    String suffix = explicit == null ? "AND j.id<? ORDER BY j.id DESC LIMIT 1" : "AND j.job_no=?";
    return jdbc
        .query(
            "SELECT p.*,j.job_no FROM cae_result_parse_record p JOIN cae_job j ON j.id=p.job_id JOIN cae_model_revision revision ON revision.id=j.model_revision_id WHERE j.status='SUCCESS' AND j.id<>? AND j.project_id=? AND revision.model_id=(SELECT model_id FROM cae_model_revision WHERE id=?) AND j.analysis_type=? AND j.condition_code=? AND p.status='COMPLETE' AND p.parser_version=? AND p.subcase=? "
                + suffix,
            SqlRows.MAPPER,
            SqlRows.id(job),
            job.get("projectId"),
            job.get("modelRevisionId"),
            job.get("analysisType"),
            job.get("conditionCode"),
            parse.get("parserVersion"),
            parse.get("subcase"),
            explicit == null ? SqlRows.id(job) : explicit)
        .stream()
        .findFirst();
  }

  public Optional<Map<String, Object>> find(long parse, String kind, int version, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM cae_pipeline_stage WHERE parse_record_id=? AND kind=? AND stage_version=?"
                + (lock ? " FOR UPDATE" : ""),
            SqlRows.MAPPER,
            parse,
            kind,
            version)
        .stream()
        .findFirst()
        .map(this::decode);
  }

  public Map<String, Object> create(
      Map<String, Object> job,
      Map<String, Object> parse,
      String kind,
      int version,
      int postVersion,
      Map<String, Object> request) {
    jdbc.update(
        "INSERT INTO cae_pipeline_stage(job_id,execution_id,parse_record_id,parser_version,kind,stage_version,post_version,request_json,request_fingerprint,pipeline_generation) VALUES(?,?,?,?,?,?,?,?,?,?)",
        SqlRows.id(job),
        parse.get("executionId"),
        SqlRows.id(parse),
        parse.get("parserVersion"),
        kind,
        version,
        postVersion,
        json.write(request),
        fingerprints.of(request),
        job.get("pipelineGeneration"));
    return find(SqlRows.id(parse), kind, version, true).orElseThrow();
  }

  public Map<String, Object> claim(Map<String, Object> row) {
    String owner = UUID.randomUUID().toString();
    jdbc.update(
        "UPDATE cae_pipeline_stage SET status='RUNNING',claim_owner=?,claim_generation=claim_generation+1,claim_until=?,failure_message=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
        owner,
        Timestamp.from(Instant.now().plusSeconds(90)),
        SqlRows.id(row));
    return find(
            ((Number) row.get("parseRecordId")).longValue(),
            row.get("kind").toString(),
            ((Number) row.get("stageVersion")).intValue(),
            true)
        .orElseThrow();
  }

  public boolean owns(Map<String, Object> current, Map<String, Object> claim) {
    return "RUNNING".equals(current.get("status"))
        && java.util.Objects.equals(current.get("claimOwner"), claim.get("claimOwner"))
        && java.util.Objects.equals(current.get("claimGeneration"), claim.get("claimGeneration"))
        && current.get("claimUntil") != null
        && Instant.parse(current.get("claimUntil").toString()).isAfter(Instant.now());
  }

  public void complete(Map<String, Object> row, long manifestId) {
    jdbc.update(
        "UPDATE cae_pipeline_stage SET status='COMPLETE',manifest_artifact_id=?,claim_owner=NULL,claim_until=NULL,failure_message=NULL,completed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=?",
        manifestId,
        SqlRows.id(row));
  }

  public void fail(Map<String, Object> row, String reason) {
    jdbc.update(
        "UPDATE cae_pipeline_stage SET status='FAILED',claim_owner=NULL,claim_until=NULL,failure_message=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
        reason.substring(0, Math.min(1000, reason.length())),
        SqlRows.id(row));
  }

  public void next(long id) {
    jdbc.update(
        "UPDATE cae_pipeline_stage SET next_requested_at=UTC_TIMESTAMP(6) WHERE id=? AND next_requested_at IS NULL",
        id);
  }

  public int nextVersion(long parse) {
    return jdbc.queryForObject(
        "SELECT COALESCE(MAX(stage_version),0)+1 FROM cae_pipeline_stage WHERE parse_record_id=?",
        Integer.class,
        parse);
  }

  public void reset(Map<String, Object> stage, long generation) {
    jdbc.update(
        "UPDATE cae_pipeline_stage SET status='REQUESTED',claim_owner=NULL,claim_until=NULL,pipeline_generation=?,failure_message=NULL WHERE id=?",
        generation,
        SqlRows.id(stage));
  }

  public List<Map<String, Object>> stages(long job) {
    return jdbc
        .query("SELECT * FROM cae_pipeline_stage WHERE job_id=? ORDER BY id", SqlRows.MAPPER, job)
        .stream()
        .map(this::decode)
        .toList();
  }

  public void report(Map<String, Object> stage, long artifact) {
    Map<String, Object> payload = json.readMap(stage.get("request"));
    Map<String, Object> baseline = json.readMap(payload.get("baseline"));
    jdbc.update(
        "INSERT INTO cae_report(job_id,execution_id,parse_record_id,parser_version,report_version,post_version,stage_id,artifact_id,template_code,template_version,baseline_job_no,context_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        stage.get("jobId"),
        stage.get("executionId"),
        stage.get("parseRecordId"),
        stage.get("parserVersion"),
        stage.get("stageVersion"),
        stage.get("postVersion"),
        SqlRows.id(stage),
        artifact,
        payload.get("templateCode"),
        payload.get("templateVersion"),
        baseline.get("jobNo"),
        json.write(payload));
  }

  public List<Map<String, Object>> reports(Long job, Long project, String username, boolean admin) {
    return jdbc
        .query(
            "SELECT r.*,j.job_no,j.project_id,j.condition_code,j.analysis_type FROM cae_report r JOIN cae_job j ON j.id=r.job_id WHERE (? IS NULL OR r.job_id=?) AND (? IS NULL OR j.project_id=?) AND (? OR EXISTS(SELECT 1 FROM cae_project_member m WHERE m.project_id=j.project_id AND m.username=?)) ORDER BY r.id DESC LIMIT 500",
            SqlRows.MAPPER,
            job,
            job,
            project,
            project,
            admin,
            username)
        .stream()
        .map(this::publicReport)
        .toList();
  }

  public Map<String, Object> report(long id, boolean lock) {
    return jdbc
        .query(
            "SELECT r.*,j.job_no,j.project_id,j.condition_code,j.analysis_type FROM cae_report r JOIN cae_job j ON j.id=r.job_id WHERE r.id=?"
                + (lock ? " FOR UPDATE" : ""),
            SqlRows.MAPPER,
            id)
        .stream()
        .findFirst()
        .map(this::publicReport)
        .orElseThrow(() -> ApiException.notFound("Report"));
  }

  public void review(long id, String actor, boolean approve) {
    jdbc.update(
        "UPDATE cae_report SET report_status=?,"
            + (approve
                ? "approver=?,approved_at=UTC_TIMESTAMP(6)"
                : "reviewer=?,reviewed_at=UTC_TIMESTAMP(6)")
            + " WHERE id=?",
        approve ? "APPROVED" : "REVIEWED",
        actor,
        id);
  }

  private Map<String, Object> publicReport(Map<String, Object> row) {
    row.put("context", json.readMap(row.remove("contextJson")));
    row.put("downloadUrl", "/api/v1/artifacts/" + row.get("artifactId") + "/download");
    return row;
  }

  private Map<String, Object> decode(Map<String, Object> row) {
    row.put("request", json.readMap(row.remove("requestJson")));
    return row;
  }
}
