package com.example.cae.job.submission;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.job.validation.CaseValidationService;
import com.example.cae.job.validation.InputSnapshotService;
import com.example.cae.report.PipelineService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists an explicit user scheduling intent before a background worker starts an attempt. */
@Service
public class JobCommandService {
  private final JobRepository jobs;
  private final ProjectAccess access;
  private final CaseValidationService validation;
  private final InputSnapshotService snapshots;
  private final TransactionTemplate tx;
  private final JdbcTemplate jdbc;
  private final JsonCodec json;
  private final PipelineService pipeline;

  public JobCommandService(
      JobRepository jobs,
      ProjectAccess access,
      CaseValidationService validation,
      InputSnapshotService snapshots,
      TransactionTemplate tx,
      JdbcTemplate jdbc,
      JsonCodec json,
      PipelineService pipeline) {
    this.jobs = jobs;
    this.access = access;
    this.validation = validation;
    this.snapshots = snapshots;
    this.tx = tx;
    this.jdbc = jdbc;
    this.json = json;
    this.pipeline = pipeline;
  }

  public Map<String, Object> submit(Authentication user, String jobNo, SubmissionRequest request) {
    return request(user, jobNo, request, false);
  }

  public Map<String, Object> retry(Authentication user, String jobNo, SubmissionRequest request) {
    return request(user, jobNo, request, true);
  }

  private Map<String, Object> request(
      Authentication user, String jobNo, SubmissionRequest supplied, boolean retry) {
    SubmissionRequest command = supplied == null ? SubmissionRequest.empty() : supplied;
    Map<String, Object> job = jobs.findByNo(jobNo);
    access.requireOwner(user, job);
    String key =
        command.idempotencyKey() == null ? UUID.randomUUID().toString() : command.idempotencyKey();
    if (!key.matches("[A-Za-z0-9_-]{1,100}")) throw ApiException.invalid("Invalid idempotency key");
    String operation = retry ? "RETRY" : "SUBMIT";
    Map<String, Object> override =
        command.mock() == null ? null : MockExecutionOptions.validate(command.mock());
    String fingerprint =
        fingerprint(
            operation + "\n" + json.write(override == null ? Map.of() : new TreeMap<>(override)));
    return tx.execute(
        status -> {
          Map<String, Object> current = jobs.lockById(SqlRows.id(job));
          List<Map<String, Object>> old =
              jdbc.queryForList(
                  "SELECT operation,fingerprint FROM cae_job_submission_request WHERE job_id=? AND idempotency_key=?",
                  SqlRows.id(current),
                  key);
          if (!old.isEmpty()) {
            if (!operation.equals(old.get(0).get("operation"))
                || !fingerprint.equals(old.get(0).get("fingerprint")))
              throw ApiException.conflict(
                  "Idempotency key was already used for a different command");
            return current;
          }
          JobStatus state = JobStatus.valueOf(current.get("status").toString());
          if (Set.of(
                  JobStatus.VALIDATING,
                  JobStatus.QUEUED,
                  JobStatus.WAITING_RESOURCE,
                  JobStatus.SUBMITTING,
                  JobStatus.SUBMITTED,
                  JobStatus.RUNNING,
                  JobStatus.UNKNOWN,
                  JobStatus.CANCEL_REQUESTED,
                  JobStatus.POST_PROCESSING,
                  JobStatus.REPORTING)
              .contains(state)) {
            if (override != null)
              throw ApiException.conflict("Active execution options cannot be changed");
            record(current, key, operation, fingerprint, user.getName());
            return current;
          }
          if (!retry
              && state != JobStatus.CREATED
              && state != JobStatus.READY
              && state != JobStatus.VALIDATION_FAILED)
            throw ApiException.conflict("Use retry for a failed or cancelled Job");
          if (retry
              && !Set.of(
                      JobStatus.VALIDATION_FAILED,
                      JobStatus.SUBMIT_FAILED,
                      JobStatus.RUNTIME_FAILED,
                      JobStatus.POST_PROCESS_FAILED,
                      JobStatus.REPORT_FAILED,
                      JobStatus.CANCELLED)
                  .contains(state))
            throw ApiException.conflict("Job has no retryable failed stage");
          if (state == JobStatus.POST_PROCESS_FAILED || state == JobStatus.REPORT_FAILED) {
            if (override != null)
              throw ApiException.conflict("Stage retry cannot change Solver options");
            record(current, key, operation, fingerprint, user.getName());
            return pipeline.retryLocked(current, user.getName());
          }
          if (override != null) current = jobs.patch(current, Map.of("mockJson", override));
          MockExecutionOptions.validate(json.readMap(current.get("mock")));
          record(current, key, operation, fingerprint, user.getName());
          // Validation is picked up separately; filesystem work never holds this intent
          // transaction.
          if (state == JobStatus.CREATED
              || state == JobStatus.VALIDATION_FAILED
              || (state == JobStatus.CANCELLED && current.get("workspace") == null)) {
            jobs.recordEvent(
                SqlRows.id(current),
                "JOB_VALIDATION_REQUESTED",
                Map.of("retry", retry, "idempotencyKey", key));
            return current;
          }
          snapshots.verifySnapshot(current);
          if (state != JobStatus.READY)
            current =
                jobs.transition(
                    current, JobStatus.READY, "JOB_RETRY_REQUESTED", Map.of("idempotencyKey", key));
          return jobs.transition(
              clearFailure(current),
              JobStatus.QUEUED,
              "JOB_SUBMIT_REQUESTED",
              Map.of("idempotencyKey", key));
        });
  }

  /** User intent exists, but validation itself is isolated from the short database transaction. */
  public Map<String, Object> finishValidation(long jobId) {
    Map<String, Object> before = jobs.findById(jobId);
    if (!Set.of("CREATED", "VALIDATION_FAILED", "CANCELLED").contains(before.get("status")))
      return before;
    Map<String, Object> checked = validation.validate(before);
    if (!"READY".equals(checked.get("status"))) return checked;
    return tx.execute(
        status -> {
          Map<String, Object> current = jobs.lockById(jobId);
          if (!"READY".equals(current.get("status"))) return current;
          return jobs.transition(
              current, JobStatus.QUEUED, "JOB_SUBMIT_REQUESTED", Map.of("validated", true));
        });
  }

  public List<Long> pendingValidation() {
    return jdbc.queryForList(
        "SELECT j.id FROM cae_job j WHERE j.status IN ('CREATED','VALIDATION_FAILED','CANCELLED') AND EXISTS(SELECT 1 FROM cae_job_submission_request r WHERE r.job_id=j.id AND r.created_at>=j.updated_at) ORDER BY j.priority DESC,j.id LIMIT 20",
        Long.class);
  }

  private void record(
      Map<String, Object> job, String key, String operation, String fingerprint, String actor) {
    jdbc.update(
        "INSERT INTO cae_job_submission_request(job_id,idempotency_key,operation,fingerprint) VALUES(?,?,?,?)",
        SqlRows.id(job),
        key,
        operation,
        fingerprint);
    jobs.audit(
        actor,
        operation.equals("RETRY") ? "JOB_RETRY" : "JOB_SUBMIT",
        "JOB",
        job.get("jobNo").toString(),
        Map.of("status", job.get("status")),
        Map.of("idempotencyKey", key));
  }

  private Map<String, Object> clearFailure(Map<String, Object> current) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("failureCode", null);
    fields.put("failureMessage", null);
    fields.put("rawSolverCode", null);
    fields.put("rawMessage", null);
    fields.put("finishedAt", null);
    return jobs.patch(current, fields);
  }

  private static String fingerprint(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
