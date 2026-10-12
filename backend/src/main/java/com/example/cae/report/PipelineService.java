package com.example.cae.report;

import com.example.cae.artifact.ArtifactRepository;
import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.messaging.CommandEnvelope;
import com.example.cae.result.ResultRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Short fenced transactions surround rendering; no stage allocates or submits a Solver. */
@Service
public class PipelineService {
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final ResultRepository results;
  private final PipelineRepository repository;
  private final PipelineRequestBuilder builder;
  private final PipelineBinding binding;
  private final RenderClient client;
  private final WorkerResponseVerifier verifier;
  private final ArtifactRepository artifacts;
  private final JsonCodec json;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;

  public PipelineService(
      JobRepository jobs,
      ExecutionRepository executions,
      ResultRepository results,
      PipelineRepository repository,
      PipelineRequestBuilder builder,
      PipelineBinding binding,
      RenderClient client,
      WorkerResponseVerifier verifier,
      ArtifactRepository artifacts,
      JsonCodec json,
      TransactionTemplate tx,
      MeterRegistry metrics) {
    this.jobs = jobs;
    this.executions = executions;
    this.results = results;
    this.repository = repository;
    this.builder = builder;
    this.binding = binding;
    this.client = client;
    this.verifier = verifier;
    this.artifacts = artifacts;
    this.json = json;
    this.tx = tx;
    this.metrics = metrics;
  }

  public void process(CommandEnvelope command, String kind) {
    Map<String, Object> job = jobs.findById(command.jobId());
    long parseId = positive(command.payload().get("parseRecordId"));
    Map<String, Object> parse = repository.parse(parseId);
    long executionId = positive(command.payload().get("executionId"));
    Map<String, Object> execution = executions.find(executionId);
    validate(command, job, parse, execution);
    if (!current(job, executionId) || "CANCELLED".equals(job.get("status"))) return;
    int version =
        Math.toIntExact(
            positive(command.payload().get(kind.equals("POST") ? "postVersion" : "reportVersion")));
    if (version > 10000) throw new IllegalArgumentException("Stage version out of range");
    var existing = repository.find(parseId, kind, version, false);
    if (existing.isPresent() && "COMPLETE".equals(existing.get().get("status"))) return;
    Map<String, Object> candidate = null;
    try {
      if (existing.isEmpty())
        candidate =
            kind.equals("POST")
                ? builder.post(job, execution, parse, version, null)
                : builder.report(
                    repository
                        .find(
                            parseId,
                            "POST",
                            Math.toIntExact(positive(command.payload().get("postVersion"))),
                            false)
                        .orElseThrow(StageBusyException::new),
                    version);
    } catch (DataAccessException unavailable) {
      throw unavailable;
    } catch (StageBusyException busy) {
      throw busy;
    } catch (RuntimeException failure) {
      preparationFailed(job, parse, kind, version, failure);
      throw failure;
    }
    Map<String, Object> prepared = candidate;
    Map<String, Object> claim =
        tx.execute(
            status -> {
              var locked = jobs.lockById(command.jobId());
              var confirmed = executions.locked(executionId);
              if (!current(locked, executionId)
                  || !"SUCCESS".equals(confirmed.get("status"))
                  || "CANCELLED".equals(locked.get("status"))) return Map.of();
              if (locked.get("activePipelineParseId") == null)
                locked = binding.completed(locked, parse);
              var prior = repository.find(parseId, kind, version, true);
              var stage =
                  prior.isPresent()
                      ? prior.get()
                      : repository.create(
                          locked,
                          parse,
                          kind,
                          version,
                          Math.toIntExact(positive(command.payload().get("postVersion"))),
                          prepared);
              if ("COMPLETE".equals(stage.get("status"))) return Map.of();
              if ("RUNNING".equals(stage.get("status"))
                  && stage.get("claimUntil") != null
                  && Instant.parse(stage.get("claimUntil").toString()).isAfter(Instant.now()))
                throw new StageBusyException();
              if (binding.active(locked, stage)) {
                String failed = kind.equals("POST") ? "POST_PROCESS_FAILED" : "REPORT_FAILED";
                if (failed.equals(locked.get("status")))
                  jobs.transition(
                      clear(locked),
                      kind.equals("POST") ? JobStatus.POST_PROCESSING : JobStatus.REPORTING,
                      "STAGE_AUTOMATIC_RETRY",
                      Map.of("stageId", SqlRows.id(stage)));
              }
              return repository.claim(stage);
            });
    if (claim.isEmpty()) return;
    long started = System.nanoTime();
    try {
      var response =
          client.render(
              execution.get("nodeId").toString(), kind, json.readMap(claim.get("request")));
      var checked = verifier.verify(claim, response);
      tx.executeWithoutResult(
          status -> {
            var locked = jobs.lockById(command.jobId());
            executions.locked(executionId);
            var stage = repository.find(parseId, kind, version, true).orElseThrow();
            if (!repository.owns(stage, claim)) throw new StageBusyException();
            if (!current(locked, executionId)) {
              repository.fail(stage, "Obsolete execution; stage cannot modify the current Job");
              return;
            }
            long manifestId = 0, reportArtifact = 0;
            for (var item : checked) {
              var metadata = new LinkedHashMap<>(item.metadata());
              metadata.put("parseRecordId", parseId);
              metadata.put("executionId", executionId);
              metadata.put("parserVersion", parse.get("parserVersion"));
              metadata.put("rawChecksum", parse.get("rawChecksum"));
              var artifact =
                  artifacts.register(
                      command.jobId(),
                      executionId,
                      parseId,
                      parse.get("parserVersion").toString(),
                      version,
                      item.type(),
                      item.file(),
                      item.contentType(),
                      metadata);
              if (item.type().endsWith("MANIFEST")) manifestId = SqlRows.id(artifact);
              if (item.type().equals("REPORT_PPTX")) reportArtifact = SqlRows.id(artifact);
            }
            repository.complete(stage, manifestId);
            if (kind.equals("POST")) {
              if (stage.get("nextRequestedAt") == null) {
                jobs.recordEvent(
                    command.jobId(),
                    "REPORT_REQUESTED",
                    stageCommand(
                        parse, execution, ((Number) stage.get("postVersion")).intValue(), version));
                repository.next(SqlRows.id(stage));
              }
              if (binding.active(locked, stage) && "POST_PROCESSING".equals(locked.get("status")))
                jobs.transition(
                    clear(locked),
                    JobStatus.REPORTING,
                    "POST_PROCESS_COMPLETED",
                    Map.of("stageId", SqlRows.id(stage), "parseRecordId", parseId));
            } else {
              repository.report(stage, reportArtifact);
              if (binding.active(locked, stage) && "REPORTING".equals(locked.get("status"))) {
                var fields = new LinkedHashMap<String, Object>();
                fields.put("finishedAt", java.sql.Timestamp.from(Instant.now()));
                locked = jobs.patch(clear(locked), fields);
                jobs.transition(
                    locked,
                    JobStatus.SUCCESS,
                    "DRAFT_REPORT_COMPLETED",
                    Map.of(
                        "stageId",
                        SqlRows.id(stage),
                        "artifactId",
                        reportArtifact,
                        "reportStatus",
                        "DRAFT"));
              }
            }
            jobs.recordEvent(
                command.jobId(),
                kind.equals("POST") ? "POST_ARTIFACTS_REGISTERED" : "REPORT_ARTIFACTS_REGISTERED",
                Map.of("stageId", SqlRows.id(stage), "parseRecordId", parseId, "version", version));
          });
      metrics.counter("cae.stage.completed", "stage", kind).increment();
    } catch (StageBusyException busy) {
      throw busy;
    } catch (DataIntegrityViolationException constraint) {
      failed(command, claim, kind, constraint);
      throw new IllegalArgumentException("Stage violates a durable storage constraint", constraint);
    } catch (DataAccessException unavailable) {
      throw unavailable;
    } catch (RuntimeException failure) {
      failed(command, claim, kind, failure);
      throw failure;
    } finally {
      metrics
          .timer("cae.stage.duration", "stage", kind)
          .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  private void failed(
      CommandEnvelope command, Map<String, Object> claim, String kind, RuntimeException failure) {
    tx.executeWithoutResult(
        status -> {
          var job = jobs.lockById(command.jobId());
          executions.locked(((Number) claim.get("executionId")).longValue());
          var stage =
              repository
                  .find(
                      ((Number) claim.get("parseRecordId")).longValue(),
                      kind,
                      ((Number) claim.get("stageVersion")).intValue(),
                      true)
                  .orElseThrow();
          if (!repository.owns(stage, claim)) return;
          repository.fail(stage, reason(failure));
          failMain(job, stage, kind, failure);
        });
    metrics.counter("cae.stage.failed", "stage", kind).increment();
  }

  private void preparationFailed(
      Map<String, Object> job,
      Map<String, Object> parse,
      String kind,
      int version,
      RuntimeException failure) {
    tx.executeWithoutResult(
        status -> {
          var locked = jobs.lockById(SqlRows.id(job));
          executions.locked(((Number) parse.get("executionId")).longValue());
          var stage = new LinkedHashMap<String, Object>();
          stage.put("parseRecordId", SqlRows.id(parse));
          stage.put("kind", kind);
          stage.put("stageVersion", version);
          stage.put("pipelineGeneration", job.get("pipelineGeneration"));
          failMain(locked, stage, kind, failure);
        });
  }

  private void failMain(
      Map<String, Object> job, Map<String, Object> stage, String kind, RuntimeException failure) {
    if (!binding.active(job, stage)
        || !current(
            job,
            ((Number)
                    repository
                        .parse(((Number) stage.get("parseRecordId")).longValue())
                        .get("executionId"))
                .longValue())) return;
    String expected = kind.equals("POST") ? "POST_PROCESSING" : "REPORTING";
    if (!expected.equals(job.get("status"))) return;
    job =
        jobs.patch(
            job,
            Map.of(
                "failureCode",
                kind.equals("POST") ? "POST_PROCESS_FAILED" : "REPORT_GENERATION_FAILED",
                "failureMessage",
                reason(failure)));
    jobs.transition(
        job,
        kind.equals("POST") ? JobStatus.POST_PROCESS_FAILED : JobStatus.REPORT_FAILED,
        "STAGE_FAILED",
        Map.of("kind", kind, "parseRecordId", stage.get("parseRecordId")));
  }

  public Map<String, Object> retryLocked(Map<String, Object> job, String actor) {
    long jobId = SqlRows.id(job);
    long executionId = ((Number) job.get("currentExecutionId")).longValue();
    var execution = executions.locked(executionId);
    if (!"SUCCESS".equals(execution.get("status")))
      throw ApiException.conflict("Stage retry requires the original successful Solver");
    String kind = job.get("status").equals("REPORT_FAILED") ? "REPORT" : "POST";
    Object activeParseId = job.get("activePipelineParseId");
    var selected =
        results.history(jobId).stream()
            .filter(row -> Objects.equals(row.get("id"), activeParseId))
            .findFirst();
    if (selected.isEmpty() || !"COMPLETE".equals(selected.get().get("status"))) {
      var failed =
          results.history(jobId).stream()
              .filter(row -> "FAILED".equals(row.get("status")))
              .reduce((a, b) -> b)
              .orElseThrow(() -> ApiException.conflict("No failed parser stage to retry"));
      job =
          jobs.transition(
              clear(job),
              JobStatus.POST_PROCESSING,
              "PARSER_RETRY_REQUESTED",
              Map.of("parserVersion", failed.get("parserVersion")));
      jobs.recordEvent(
          jobId,
          "RESULT_PARSE_REQUESTED",
          Map.of(
              "executionId",
              executionId,
              "executionRequestId",
              execution.get("submitRequestId"),
              "parserVersion",
              failed.get("parserVersion")));
      return job;
    }
    var parse = selected.get();
    int version =
        ((Number) job.get(kind.equals("POST") ? "activePostVersion" : "activeReportVersion"))
            .intValue();
    var stage = repository.find(SqlRows.id(parse), kind, version, true);
    var fields = new LinkedHashMap<String, Object>();
    fields.put("pipelineGeneration", ((Number) job.get("pipelineGeneration")).longValue() + 1);
    job = jobs.patch(clear(job), fields);
    if (stage.isPresent())
      repository.reset(stage.get(), ((Number) job.get("pipelineGeneration")).longValue());
    job =
        jobs.transition(
            job,
            kind.equals("POST") ? JobStatus.POST_PROCESSING : JobStatus.REPORTING,
            "STAGE_RETRY_REQUESTED",
            Map.of("kind", kind, "actor", actor));
    jobs.recordEvent(
        jobId,
        kind.equals("POST") ? "POST_PROCESS_REQUESTED" : "REPORT_REQUESTED",
        stageCommand(
            parse, execution, ((Number) job.get("activePostVersion")).intValue(), version));
    return job;
  }

  public Map<String, Object> stageCommand(
      Map<String, Object> parse,
      Map<String, Object> execution,
      int postVersion,
      int reportVersion) {
    var command = new LinkedHashMap<String, Object>();
    command.put("jobNo", jobs.findById(((Number) parse.get("jobId")).longValue()).get("jobNo"));
    command.put("executionId", SqlRows.id(execution));
    command.put("executionRequestId", execution.get("submitRequestId"));
    command.put("parseRecordId", SqlRows.id(parse));
    command.put("parserVersion", parse.get("parserVersion"));
    command.put("rawChecksum", parse.get("rawChecksum"));
    command.put("conditionCode", parse.get("conditionCode"));
    command.put("subcase", parse.get("subcase"));
    command.put("postVersion", postVersion);
    command.put("reportVersion", reportVersion);
    return command;
  }

  private void validate(
      CommandEnvelope command,
      Map<String, Object> job,
      Map<String, Object> parse,
      Map<String, Object> execution) {
    if (!command.jobNo().equals(job.get("jobNo"))
        || ((Number) parse.get("jobId")).longValue() != command.jobId()
        || ((Number) execution.get("jobId")).longValue() != command.jobId()
        || !parse.get("executionId").equals(execution.get("id"))
        || !"COMPLETE".equals(parse.get("status"))
        || !"SUCCESS".equals(execution.get("status"))
        || !execution.get("submitRequestId").equals(command.payload().get("executionRequestId")))
      throw new IllegalArgumentException("Stage command source identity mismatch");
    for (String key : List.of("parserVersion", "rawChecksum", "conditionCode", "subcase"))
      if (!Objects.equals(parse.get(key), command.payload().get(key))
          && !(parse.get(key) instanceof Number
              && command.payload().get(key) instanceof Number
              && parse.get(key).toString().equals(command.payload().get(key).toString())))
        throw new IllegalArgumentException("Stage command parse metadata mismatch: " + key);
  }

  private Map<String, Object> clear(Map<String, Object> job) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("failureCode", null);
    fields.put("failureMessage", null);
    return jobs.patch(job, fields);
  }

  private static boolean current(Map<String, Object> job, long executionId) {
    return job.get("currentExecutionId") instanceof Number id && id.longValue() == executionId;
  }

  public static long positive(Object value) {
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.longValue() < 1
        || n.doubleValue() != n.longValue())
      throw new IllegalArgumentException("Expected a positive integral stage identity");
    return n.longValue();
  }

  private static String reason(RuntimeException failure) {
    String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
    return message.substring(0, Math.min(1000, message.length()));
  }

  public static class StageBusyException extends RuntimeException {
    public StageBusyException() {
      super("The original stage is still being processed");
    }
  }
}
