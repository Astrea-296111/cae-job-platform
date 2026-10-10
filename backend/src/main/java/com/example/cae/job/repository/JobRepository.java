package com.example.cae.job.repository;

import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.CaeJob;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.domain.JobTransitionPolicy;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobRepository {
  private final JdbcTemplate jdbc;
  private final JsonCodec json;
  private final JobTransitionPolicy policy;
  private static final Set<String> PATCH_COLUMNS =
      Set.of(
          "stage",
          "input_manifest_uri",
          "input_checksum",
          "workspace",
          "current_execution_id",
          "batch_id",
          "failure_code",
          "failure_message",
          "raw_solver_code",
          "raw_message",
          "submitted_at",
          "started_at",
          "finished_at",
          "priority",
          "solver_profile_version");

  public JobRepository(JdbcTemplate jdbc, JsonCodec json, JobTransitionPolicy policy) {
    this.jdbc = jdbc;
    this.json = json;
    this.policy = policy;
  }

  public Map<String, Object> findByNo(String jobNo) {
    List<Map<String, Object>> jobs =
        jdbc.query("SELECT * FROM cae_job WHERE job_no=?", SqlRows.MAPPER, jobNo);
    if (jobs.isEmpty()) throw ApiException.notFound("Job");
    return decode(jobs.get(0));
  }

  public Map<String, Object> findById(long id) {
    List<Map<String, Object>> jobs =
        jdbc.query("SELECT * FROM cae_job WHERE id=?", SqlRows.MAPPER, id);
    if (jobs.isEmpty()) throw ApiException.notFound("Job");
    return decode(jobs.get(0));
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> fields) {
    String sql =
        "INSERT INTO cae_job(job_no,project_id,model_revision_id,analysis_type,condition_code,solver_type,solver_profile,priority,status,submitter_id,input_manifest_uri,resource_profile_id,case_path,input_json,resource_json,mock_json,batch_id) VALUES(?,?,?,?,?,?,?,?,'CREATED',?,?,?,?,?,?,?,?)";
    Object[] values = {
      "NEW-" + UUID.randomUUID(),
      fields.get("projectId"),
      fields.get("modelRevisionId"),
      fields.get("analysisType"),
      fields.get("conditionCode"),
      fields.get("solverType"),
      fields.get("solverProfile"),
      fields.get("priority"),
      fields.get("submitterId"),
      fields.get("inputManifestUri"),
      fields.get("resourceProfileId"),
      fields.get("casePath"),
      json.write(fields.getOrDefault("input", Map.of())),
      json.write(fields.get("resource")),
      json.write(fields.getOrDefault("mock", Map.of())),
      fields.get("batchId")
    };
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
          return statement;
        },
        keys);
    long id = keys.getKey().longValue();
    String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
    String jobNo = "CAE-" + date + "-" + String.format("%06d", id);
    jdbc.update("UPDATE cae_job SET job_no=? WHERE id=?", jobNo, id);
    event(
        id,
        "JOB_CREATED",
        null,
        JobStatus.CREATED.name(),
        Map.of("submitter", fields.get("submitterId")));
    return findById(id);
  }

  @Transactional
  public Map<String, Object> transition(
      long id, JobStatus target, String eventType, Map<String, Object> detail) {
    return transition(findById(id), target, eventType, detail);
  }

  /** The caller may supply an earlier snapshot; a stale status or version fails the CAS. */
  @Transactional
  public Map<String, Object> transition(
      Map<String, Object> expected,
      JobStatus target,
      String eventType,
      Map<String, Object> detail) {
    CaeJob job = CaeJob.from(expected, policy);
    JobStatus from = job.status();
    if (!job.transitTo(target)) {
      Map<String, Object> current = findById(job.id());
      if (!current.get("status").equals(target.name())
          || ((Number) current.get("version")).longValue() != job.version())
        throw ApiException.conflict("Job changed concurrently; refresh its state");
      return current;
    }
    int updated =
        jdbc.update(
            "UPDATE cae_job SET status=?,stage=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status=? AND version=?",
            target.name(),
            stage(target),
            job.id(),
            from.name(),
            job.version());
    if (updated != 1) throw ApiException.conflict("Job changed concurrently; refresh its state");
    event(job.id(), eventType, from.name(), target.name(), detail);
    return findById(job.id());
  }

  @Transactional
  public Map<String, Object> patch(long id, Map<String, Object> fields) {
    return patch(findById(id), fields);
  }

  /**
   * Metadata uses the same version boundary as business status, preventing stale execution
   * associations.
   */
  @Transactional
  public Map<String, Object> patch(Map<String, Object> expected, Map<String, Object> fields) {
    long id = SqlRows.id(expected);
    if (fields.isEmpty()) return findById(id);
    List<String> assignment = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    fields.forEach(
        (field, value) -> {
          String column = snake(field);
          if (!PATCH_COLUMNS.contains(column))
            throw new IllegalArgumentException("Unsupported Job metadata field: " + field);
          assignment.add(column + "=?");
          values.add(value);
        });
    values.add(id);
    values.add(expected.get("version"));
    int updated =
        jdbc.update(
            "UPDATE cae_job SET "
                + String.join(",", assignment)
                + ",version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=?",
            values.toArray());
    if (updated != 1)
      throw ApiException.conflict("Job metadata changed concurrently; refresh its state");
    return findById(id);
  }

  public List<Map<String, Object>> listByStatus(JobStatus... statuses) {
    if (statuses.length == 0) return List.of();
    String placeholders = String.join(",", Arrays.stream(statuses).map(s -> "?").toList());
    return decode(
        jdbc.query(
            "SELECT * FROM cae_job WHERE status IN ("
                + placeholders
                + ") ORDER BY priority DESC,created_at,id",
            SqlRows.MAPPER,
            Arrays.stream(statuses).map(Enum::name).toArray()));
  }

  public List<Map<String, Object>> list(Map<String, Object> filters, int offset, int size) {
    Query query = query(filters);
    List<Object> values = new ArrayList<>(query.values());
    values.add(size);
    values.add(offset);
    return decode(
        jdbc.query(
            "SELECT j.* FROM cae_job j WHERE "
                + query.where()
                + " ORDER BY j.created_at DESC,j.id DESC LIMIT ? OFFSET ?",
            SqlRows.MAPPER,
            values.toArray()));
  }

  public long count(Map<String, Object> filters) {
    Query query = query(filters);
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM cae_job j WHERE " + query.where(),
        Long.class,
        query.values().toArray());
  }

  private Query query(Map<String, Object> filters) {
    List<String> where = new ArrayList<>(List.of("1=1"));
    List<Object> values = new ArrayList<>();
    Map<String, String> columns =
        Map.of(
            "projectId",
            "project_id",
            "analysisType",
            "analysis_type",
            "conditionCode",
            "condition_code",
            "status",
            "status",
            "submitter",
            "submitter_id",
            "batchId",
            "batch_id");
    columns.forEach(
        (key, column) -> {
          if (filters.get(key) != null) {
            where.add("j." + column + "=?");
            values.add(filters.get(key));
          }
        });
    if (filters.get("username") != null) {
      where.add(
          "EXISTS (SELECT 1 FROM cae_project_member m WHERE m.project_id=j.project_id AND m.username=?)");
      values.add(filters.get("username"));
    }
    if (filters.get("createdFrom") != null) {
      where.add("j.created_at>=?");
      values.add(filters.get("createdFrom"));
    }
    if (filters.get("createdTo") != null) {
      where.add("j.created_at<?");
      values.add(filters.get("createdTo"));
    }
    return new Query(String.join(" AND ", where), values);
  }

  private record Query(String where, List<Object> values) {}

  public List<Map<String, Object>> events(long jobId) {
    List<Map<String, Object>> events =
        jdbc.query("SELECT * FROM cae_job_event WHERE job_id=? ORDER BY id", SqlRows.MAPPER, jobId);
    events.forEach(e -> e.put("detail", json.readMap(e.remove("detailJson"))));
    return events;
  }

  public void recordEvent(long jobId, String eventType, Map<String, Object> detail) {
    event(jobId, eventType, null, null, detail);
  }

  private void event(
      long jobId, String eventType, String from, String to, Map<String, Object> detail) {
    jdbc.update(
        "INSERT INTO cae_job_event(job_id,event_type,from_status,to_status,detail_json) VALUES(?,?,?,?,?)",
        jobId,
        eventType,
        from,
        to,
        json.write(detail == null ? Map.of() : detail));
  }

  public void audit(
      String actor,
      String operation,
      String targetType,
      String targetId,
      Object before,
      Object after) {
    jdbc.update(
        "INSERT INTO cae_audit_log(actor,operation,target_type,target_id,before_json,after_json) VALUES(?,?,?,?,?,?)",
        actor,
        operation,
        targetType,
        targetId,
        before == null ? null : json.write(before),
        after == null ? null : json.write(after));
  }

  private List<Map<String, Object>> decode(List<Map<String, Object>> rows) {
    rows.forEach(this::decode);
    return rows;
  }

  private Map<String, Object> decode(Map<String, Object> row) {
    row.put("input", json.readMap(row.remove("inputJson")));
    row.put("resource", json.readMap(row.remove("resourceJson")));
    row.put("mock", json.readMap(row.remove("mockJson")));
    return row;
  }

  private String snake(String field) {
    return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
  }

  private String stage(JobStatus target) {
    return switch (target) {
      case CREATED -> "CREATED";
      case VALIDATING, VALIDATION_FAILED -> "VALIDATION";
      case READY, WAITING_RESOURCE, QUEUED -> "SCHEDULING";
      case SUBMITTING, SUBMITTED, SUBMIT_FAILED -> "SUBMISSION";
      case RUNNING, RUNTIME_FAILED, UNKNOWN -> "COMPUTE";
      case POST_PROCESSING, POST_PROCESS_FAILED -> "POST";
      case REPORTING, REPORT_FAILED -> "REPORT";
      case CANCEL_REQUESTED, CANCELLED -> "CANCEL";
      case SUCCESS -> "COMPLETE";
    };
  }
}
