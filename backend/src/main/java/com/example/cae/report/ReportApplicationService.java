package com.example.cae.report;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.result.ResultRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReportApplicationService {
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final ProjectAccess access;
  private final ResultRepository results;
  private final PipelineRepository repository;
  private final PipelineRequestBuilder builder;
  private final PipelineService pipeline;
  private final RenderFingerprint fingerprints;
  private final TransactionTemplate tx;

  public ReportApplicationService(
      JobRepository jobs,
      ExecutionRepository executions,
      ProjectAccess access,
      ResultRepository results,
      PipelineRepository repository,
      PipelineRequestBuilder builder,
      PipelineService pipeline,
      RenderFingerprint fingerprints,
      TransactionTemplate tx) {
    this.jobs = jobs;
    this.executions = executions;
    this.access = access;
    this.results = results;
    this.repository = repository;
    this.builder = builder;
    this.pipeline = pipeline;
    this.fingerprints = fingerprints;
    this.tx = tx;
  }

  public List<Map<String, Object>> reports(Authentication user, Long project, String jobNo) {
    Long jobId = null;
    if (jobNo != null) {
      var job = jobs.findByNo(jobNo);
      access.requireJob(user, job);
      jobId = SqlRows.id(job);
    }
    if (project != null) access.require(user, project);
    return repository.reports(jobId, project, user.getName(), access.admin(user));
  }

  public Map<String, Object> detail(Authentication user, long id) {
    var report = repository.report(id, false);
    access.require(user, ((Number) report.get("projectId")).longValue());
    return report;
  }

  public List<Map<String, Object>> stages(Authentication user, String jobNo) {
    var job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    return repository.stages(SqlRows.id(job));
  }

  public Map<String, Object> generate(
      Authentication user, String jobNo, String version, String baseline, String key) {
    var job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    if (baseline != null) {
      access.requireLead(user, ((Number) job.get("projectId")).longValue());
      access.requireJob(user, jobs.findByNo(baseline));
    }
    var parse =
        results
            .find(SqlRows.id(job), version)
            .orElseThrow(() -> ApiException.notFound("Parser version"));
    if (!"COMPLETE".equals(parse.get("status"))
        || !java.util.Objects.equals(job.get("currentExecutionId"), parse.get("executionId")))
      throw ApiException.conflict("A complete current parser version is required");
    var execution = executions.find(((Number) parse.get("executionId")).longValue());
    var intent = new LinkedHashMap<String, Object>();
    intent.put("parserVersion", version);
    intent.put("baselineJobNo", baseline);
    String fingerprint = fingerprints.of(intent);
    var old = repository.intent(SqlRows.id(job), key);
    if (old.isPresent()) return replay(old.get(), fingerprint);
    if (!Set.of("SUCCESS", "POST_PROCESS_FAILED", "REPORT_FAILED").contains(job.get("status")))
      throw ApiException.conflict("Wait for the current report pipeline to finish or fail");
    var prepared = builder.post(job, execution, parse, 1, baseline);
    return tx.execute(
        status -> {
          var locked = jobs.lockById(SqlRows.id(job));
          executions.locked(SqlRows.id(execution));
          var existing = repository.intent(SqlRows.id(job), key);
          if (existing.isPresent()) return replay(existing.get(), fingerprint);
          if (!java.util.Objects.equals(locked.get("currentExecutionId"), execution.get("id"))
              || !Set.of("SUCCESS", "POST_PROCESS_FAILED", "REPORT_FAILED")
                  .contains(locked.get("status")))
            throw ApiException.conflict("Job changed before report generation was requested");
          int next = repository.nextVersion(SqlRows.id(parse));
          prepared.put("postVersion", next);
          if (!"SUCCESS".equals(locked.get("status"))) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("activePipelineParseId", SqlRows.id(parse));
            fields.put("activePostVersion", next);
            fields.put("activeReportVersion", next);
            fields.put(
                "pipelineGeneration", ((Number) locked.get("pipelineGeneration")).longValue() + 1);
            fields.put("failureCode", null);
            fields.put("failureMessage", null);
            locked = jobs.patch(locked, fields);
            locked =
                jobs.transition(
                    locked,
                    JobStatus.POST_PROCESSING,
                    "REPORT_PIPELINE_ACTIVATED",
                    Map.of("parseRecordId", SqlRows.id(parse), "version", next));
          }
          var stage = repository.create(locked, parse, "POST", next, next, prepared);
          repository.intent(SqlRows.id(job), key, fingerprint, SqlRows.id(parse), next);
          jobs.recordEvent(
              SqlRows.id(job),
              "POST_PROCESS_REQUESTED",
              pipeline.stageCommand(parse, execution, next, next));
          jobs.audit(
              user.getName(),
              "REPORT_GENERATE",
              "JOB",
              jobNo,
              null,
              Map.of("parseRecordId", SqlRows.id(parse), "version", next, "idempotencyKey", key));
          return stage;
        });
  }

  private Map<String, Object> replay(Map<String, Object> intent, String fingerprint) {
    if (!fingerprint.equals(intent.get("requestFingerprint")))
      throw ApiException.conflict("Report generation idempotency key belongs to different inputs");
    return repository
        .find(
            ((Number) intent.get("parseRecordId")).longValue(),
            "POST",
            ((Number) intent.get("stageVersion")).intValue(),
            false)
        .orElseThrow();
  }

  public Map<String, Object> review(Authentication user, long id, boolean approve) {
    var before = repository.report(id, false);
    long project = ((Number) before.get("projectId")).longValue();
    access.requireLead(user, project);
    if (approve && !access.admin(user)) throw ApiException.forbidden();
    return tx.execute(
        status -> {
          jobs.lockById(((Number) before.get("jobId")).longValue());
          var report = repository.report(id, true);
          String current = report.get("reportStatus").toString();
          if (approve && current.equals("DRAFT"))
            throw ApiException.conflict("Review the draft before approval");
          if ((approve && current.equals("APPROVED")) || (!approve && !current.equals("DRAFT")))
            return report;
          repository.review(id, user.getName(), approve);
          var updated = repository.report(id, false);
          jobs.audit(
              user.getName(),
              approve ? "REPORT_APPROVE" : "REPORT_REVIEW",
              "REPORT",
              Long.toString(id),
              Map.of("reportStatus", current),
              Map.of("reportStatus", updated.get("reportStatus")));
          return updated;
        });
  }
}
