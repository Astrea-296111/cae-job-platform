package com.example.cae.result;

import com.example.cae.artifact.ArtifactFileStore;
import com.example.cae.artifact.ArtifactFileStore.FileDescriptor;
import com.example.cae.artifact.ArtifactFileStore.JsonDocument;
import com.example.cae.artifact.ArtifactRepository;
import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.PlatformFailure;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.AnalysisType;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.job.validation.InputSnapshotService;
import com.example.cae.monitoring.RemoteExecutionStateService;
import com.example.cae.report.PipelineBinding;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Parser work reads confirmed files; no method in this service can submit a Solver. */
@Service
public class ResultParsingService {
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final ResultRepository results;
  private final ResultParserRegistry registry;
  private final ArtifactRepository artifacts;
  private final ArtifactFileStore files;
  private final InputSnapshotService snapshots;
  private final RemoteExecutionStateService remoteStates;
  private final PipelineBinding pipelineBinding;
  private final ProjectAccess access;
  private final JsonCodec json;
  private final TransactionTemplate tx;

  public ResultParsingService(
      JobRepository jobs,
      ExecutionRepository executions,
      ResultRepository results,
      ResultParserRegistry registry,
      ArtifactRepository artifacts,
      ArtifactFileStore files,
      InputSnapshotService snapshots,
      RemoteExecutionStateService remoteStates,
      PipelineBinding pipelineBinding,
      ProjectAccess access,
      JsonCodec json,
      TransactionTemplate tx) {
    this.jobs = jobs;
    this.executions = executions;
    this.results = results;
    this.registry = registry;
    this.artifacts = artifacts;
    this.files = files;
    this.snapshots = snapshots;
    this.remoteStates = remoteStates;
    this.pipelineBinding = pipelineBinding;
    this.access = access;
    this.json = json;
    this.tx = tx;
  }

  public ParseOutcome parse(long jobId, long executionId, String parserVersion) {
    ResultParserRegistry.validateVersion(parserVersion);
    Map<String, Object> job = jobs.findById(jobId);
    Map<String, Object> execution = executions.find(executionId);
    if (((Number) execution.get("jobId")).longValue() != jobId)
      throw new IllegalArgumentException("Parse execution belongs to another Job");
    if (!isCurrent(job, executionId)) return new ParseOutcome(false, true, Map.of());
    job = repairKnownSuccess(job, execution);
    if (!isCurrent(job, executionId)) return new ParseOutcome(false, true, Map.of());
    if (!parseStage(job)) return new ParseOutcome(false, true, Map.of());
    if (!"SUCCESS".equals(execution.get("status")))
      throw new IllegalArgumentException("Only confirmed successful Solver output can be parsed");
    Prepared prepared = null;
    try {
      prepared = prepare(job, execution, parserVersion);
      ParseResult parsed =
          registry
              .select(
                  AnalysisType.valueOf(job.get("analysisType").toString()),
                  job.get("solverType").toString(),
                  parserVersion)
              .parse(prepared.context());
      List<ArtifactSpec> derived = writeResults(prepared, parsed, parserVersion);
      // A second snapshot/file verification precedes the short DB commit, without holding locks.
      Prepared checked = prepare(job, execution, parserVersion);
      if (!prepared.raw().checksum().equals(checked.raw().checksum()))
        throw new IllegalArgumentException("Raw result changed during parsing");
      Prepared stable = checked;
      return tx.execute(
          status -> commit(jobId, executionId, parserVersion, stable, parsed, derived));
    } catch (IllegalArgumentException | PlatformFailure | DataIntegrityViolationException failure) {
      String checksum = prepared == null ? "0".repeat(64) : prepared.raw().checksum();
      int subcase = prepared == null ? 1 : prepared.context().subcase();
      recordFailure(jobId, executionId, parserVersion, checksum, subcase, failure.getMessage());
      if (failure instanceof DataIntegrityViolationException)
        throw new IllegalArgumentException(
            "Parsed result violates a durable storage constraint", failure);
      throw failure;
    }
  }

  public Map<String, Object> reparse(Authentication user, String jobNo, String parserVersion) {
    try {
      ResultParserRegistry.validateVersion(parserVersion);
    } catch (IllegalArgumentException invalid) {
      throw ApiException.invalid(invalid.getMessage());
    }
    Map<String, Object> job = jobs.findByNo(jobNo);
    access.requireOwner(user, job);
    if (!(job.get("currentExecutionId") instanceof Number id)
        || !Set.of(
                "POST_PROCESSING", "POST_PROCESS_FAILED", "REPORTING", "REPORT_FAILED", "SUCCESS")
            .contains(job.get("status")))
      throw ApiException.conflict("A completed Solver execution is required for reparse");
    Map<String, Object> execution = executions.find(id.longValue());
    Prepared prepared;
    try {
      prepared = prepare(job, execution, parserVersion);
    } catch (IllegalArgumentException | PlatformFailure invalid) {
      throw ApiException.invalid(invalid.getMessage());
    }
    return tx.execute(
        status -> {
          Map<String, Object> locked = jobs.lockById(SqlRows.id(job));
          Map<String, Object> current = executions.locked(id.longValue());
          if (!isCurrent(locked, id.longValue()) || !"SUCCESS".equals(current.get("status")))
            throw ApiException.conflict("Current execution changed before reparse was requested");
          var existing = results.find(SqlRows.id(locked), parserVersion);
          if (existing.isPresent() && !"FAILED".equals(existing.get().get("status"))) {
            requireSameSource(existing.get(), id.longValue(), prepared.raw().checksum());
            return existing.get();
          }
          Map<String, Object> record =
              results.request(
                  SqlRows.id(locked),
                  id.longValue(),
                  parserVersion,
                  prepared.raw().checksum(),
                  locked.get("analysisType").toString(),
                  locked.get("conditionCode").toString(),
                  prepared.context().subcase(),
                  locked.get("analysisType").equals("STRUCTURE") ? "COMPLETE" : "PILOT");
          jobs.recordEvent(
              SqlRows.id(locked),
              "RESULT_PARSE_REQUESTED",
              Map.of(
                  "executionId",
                  id.longValue(),
                  "executionRequestId",
                  current.get("submitRequestId"),
                  "parserVersion",
                  parserVersion,
                  "parseRecordId",
                  SqlRows.id(record),
                  "source",
                  "USER_REPARSE"));
          jobs.audit(
              user.getName(),
              "RESULT_REPARSE",
              "JOB",
              jobNo,
              null,
              Map.of(
                  "parserVersion",
                  parserVersion,
                  "executionId",
                  id.longValue(),
                  "rawChecksum",
                  prepared.raw().checksum()));
          return record;
        });
  }

  private ParseOutcome commit(
      long jobId,
      long executionId,
      String version,
      Prepared prepared,
      ParseResult parsed,
      List<ArtifactSpec> derived) {
    Map<String, Object> job = jobs.lockById(jobId);
    Map<String, Object> execution = executions.locked(executionId);
    if (!isCurrent(job, executionId)) return new ParseOutcome(false, true, Map.of());
    job = repairKnownSuccess(job, execution);
    if (!isCurrent(job, executionId)) return new ParseOutcome(false, true, Map.of());
    if (!parseStage(job)) return new ParseOutcome(false, true, Map.of());
    if (!"SUCCESS".equals(execution.get("status")))
      throw new IllegalArgumentException("Execution is no longer a confirmed success");
    var existing = results.find(jobId, version);
    if (existing.isPresent() && "COMPLETE".equals(existing.get().get("status"))) {
      requireSameSource(existing.get(), executionId, prepared.raw().checksum());
      return new ParseOutcome(false, false, existing.get());
    }
    Map<String, Object> record =
        results.request(
            jobId,
            executionId,
            version,
            prepared.raw().checksum(),
            job.get("analysisType").toString(),
            job.get("conditionCode").toString(),
            prepared.context().subcase(),
            parsed.maturity());
    record = results.complete(record, parsed);
    long parseId = SqlRows.id(record);
    Map<String, Object> sourceMetadata =
        identity(job, execution, prepared.context().subcase(), prepared.raw().checksum());
    artifacts.register(
        jobId,
        executionId,
        null,
        "",
        0,
        "RAW_RESULT",
        filesDescriptor(prepared.raw()),
        "application/json",
        sourceMetadata);
    artifacts.register(
        jobId,
        executionId,
        null,
        "",
        0,
        "SOLVER_LOG",
        prepared.log(),
        "text/plain",
        sourceMetadata);
    for (ArtifactSpec artifact : derived) {
      Map<String, Object> metadata = new LinkedHashMap<>(sourceMetadata);
      metadata.put("parserVersion", version);
      metadata.put("maturity", parsed.maturity());
      artifacts.register(
          jobId,
          executionId,
          parseId,
          version,
          0,
          artifact.type(),
          artifact.file(),
          artifact.contentType(),
          metadata);
    }
    job = pipelineBinding.completed(job, record);
    jobs.recordEvent(
        jobId,
        "RESULT_PARSED",
        Map.of(
            "parseRecordId",
            parseId,
            "executionId",
            executionId,
            "parserVersion",
            version,
            "metricCount",
            parsed.metrics().size(),
            "maturity",
            parsed.maturity()));
    Map<String, Object> command = new LinkedHashMap<>(sourceMetadata);
    command.put("parseRecordId", parseId);
    command.put("parserVersion", version);
    command.put("postVersion", 1);
    jobs.recordEvent(jobId, "POST_PROCESS_REQUESTED", command);
    results.markPostRequested(parseId);
    return new ParseOutcome(true, false, results.find(jobId, version).orElseThrow());
  }

  private Prepared prepare(Map<String, Object> job, Map<String, Object> execution, String version) {
    if (!"SUCCESS".equals(execution.get("status")))
      throw new IllegalArgumentException("Parser requires confirmed execution SUCCESS");
    registry.select(
        AnalysisType.valueOf(job.get("analysisType").toString()),
        job.get("solverType").toString(),
        version);
    Path jobRoot = files.jobRoot(job.get("jobNo").toString());
    String requestId = execution.get("submitRequestId").toString();
    if (!requestId.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("Invalid execution request identity");
    Path workspace = jobRoot.resolve("executions").resolve(requestId);
    files.check(workspace);
    requireEqual(jobRoot.toString(), job.get("workspace"), "Job workspace");
    requireEqual(workspace.toString(), execution.get("workspace"), "Execution workspace");
    snapshots.verifySnapshot(job);
    JsonDocument manifest = files.readJson(jobRoot.resolve("input/manifest.json"));
    requireEqual(
        job.get("analysisType"), manifest.value().get("analysisType"), "Manifest analysis");
    requireEqual(
        job.get("conditionCode"), manifest.value().get("conditionCode"), "Manifest condition");
    Map<String, Object> parameters = json.readMap(manifest.value().get("parameters"));
    int subcase =
        AbstractResultParser.integer(parameters.getOrDefault("subcase", 1), "manifest subcase");
    if (subcase < 1 || subcase > 1000)
      throw new IllegalArgumentException("Manifest subcase is outside its allowed range");
    JsonDocument config = files.readJson(workspace.resolve("execution-config.json"));
    Map<String, Object> request = json.readMap(execution.get("request"));
    requireEqual(job.get("jobNo"), request.get("jobNo"), "Immutable request Job identity");
    requireEqual(
        requestId, request.get("executionRequestId"), "Immutable request execution identity");
    for (String field :
        List.of(
            "jobNo",
            "executionRequestId",
            "solverProfile",
            "analysisType",
            "conditionCode",
            "snapshotChecksum"))
      requireEqual(request.get(field), config.value().get(field), "Execution config " + field);
    requireEqual(jobRoot.toString(), request.get("workspace"), "Immutable request workspace");
    requireEqual(
        job.get("inputChecksum"), request.get("snapshotChecksum"), "Immutable request checksum");
    requireEqual(
        execution.get("externalExecutionId"),
        config.value().get("externalExecutionId"),
        "Execution config remote identity");
    requireEqual(
        execution.get("nodeId"), config.value().get("nodeId"), "Execution config node identity");
    requireEqual(
        workspace.toString(), config.value().get("workspace"), "Execution config workspace");
    requireEqual(
        jobRoot.resolve("input").toString(),
        config.value().get("inputDirectory"),
        "Execution input directory");
    for (var entry : request.entrySet()) {
      if (entry.getKey().equals("resource") || entry.getKey().equals("mock")) {
        Map<String, Object> expected = json.readMap(entry.getValue());
        Map<String, Object> actual = json.readMap(config.value().get(entry.getKey()));
        expected.forEach(
            (key, value) ->
                requireEqual(value, actual.get(key), "Immutable " + entry.getKey() + "/" + key));
      }
    }
    Map<String, Object> requestMock = json.readMap(request.get("mock"));
    Map<String, Object> configMock = json.readMap(config.value().get("mock"));
    // Worker may fill an omitted seed with null; it cannot invent a different immutable seed.
    requireEqual(requestMock.get("seed"), configMock.get("seed"), "Immutable mock seed");
    requireEqual(parameters, config.value().get("parameters"), "Execution config parameters");
    JsonDocument state = files.readJson(workspace.resolve("state.json"));
    requireEqual(
        requestId, state.value().get("executionRequestId"), "Solver state request identity");
    requireEqual(
        execution.get("externalExecutionId"),
        state.value().get("externalExecutionId"),
        "Solver state remote identity");
    requireEqual(workspace.toString(), state.value().get("workspace"), "Solver state workspace");
    requireEqual("SUCCESS", state.value().get("state"), "Solver state");
    if (AbstractResultParser.integer(state.value().get("exitCode"), "Solver exitCode") != 0)
      throw new IllegalArgumentException("Solver success has a nonzero exit code");
    if (execution.get("pid") != null)
      requireEqual(execution.get("pid"), state.value().get("pid"), "Solver PID");
    if (execution.get("processIdentity") != null)
      requireEqual(
          execution.get("processIdentity"),
          state.value().get("processIdentity"),
          "Solver process identity");
    JsonDocument raw = files.readJson(workspace.resolve("raw_result.json"));
    requireEqual(parameters, raw.value().get("parameters"), "Raw synthetic parameters");
    Object expectedSeed = requestMock.get("seed");
    if (expectedSeed == null) expectedSeed = parameters.getOrDefault("seed", 42);
    requireEqual(expectedSeed, raw.value().get("seed"), "Raw synthetic seed");
    return new Prepared(
        new ParseContext(job, execution, raw.value(), manifest.value(), raw.checksum(), subcase),
        raw,
        files.describe(workspace.resolve("solver.log")),
        jobRoot);
  }

  private List<ArtifactSpec> writeResults(Prepared prepared, ParseResult parsed, String version) {
    ParseContext context = prepared.context();
    Map<String, Object> result =
        new LinkedHashMap<>(
            identity(context.job(), context.execution(), context.subcase(), context.rawChecksum()));
    result.put("schemaVersion", version.equals("v1") ? 1 : 2);
    result.put("parserVersion", version);
    result.put("maturity", parsed.maturity());
    result.put("metrics", parsed.metrics());
    if (version.equals("v2"))
      result.put(
          "provenance",
          Map.of(
              "snapshotChecksum",
              context.job().get("inputChecksum"),
              "solverProfile",
              context.job().get("solverProfile"),
              "qualityPolicy",
              "physics-consistency-v2"));
    Path directory = prepared.jobRoot().resolve("derived").resolve(version);
    FileDescriptor resultJson =
        files.writeImmutable(
            directory.resolve("result.json"), json.write(result).getBytes(StandardCharsets.UTF_8));
    StringBuilder csv =
        new StringBuilder(
            "parserVersion,metricCode,metricName,valueNum,valueText,unit,dimension,status,conditionCode,subcase\n");
    for (CanonicalMetric metric : parsed.metrics()) {
      List<String> row =
          List.of(
              version,
              metric.metricCode(),
              metric.metricName(),
              metric.valueNum() == null ? "" : metric.valueNum().toPlainString(),
              metric.valueText() == null ? "" : metric.valueText(),
              metric.unit(),
              metric.dimension(),
              metric.status(),
              metric.conditionCode(),
              Integer.toString(metric.subcase()));
      csv.append(String.join(",", row.stream().map(ResultParsingService::csv).toList()))
          .append('\n');
    }
    FileDescriptor resultCsv =
        files.writeImmutable(
            directory.resolve("result.csv"), csv.toString().getBytes(StandardCharsets.UTF_8));
    return List.of(
        new ArtifactSpec("RESULT_JSON", resultJson, "application/json"),
        new ArtifactSpec("RESULT_CSV", resultCsv, "text/csv"));
  }

  private void recordFailure(
      long jobId, long executionId, String version, String checksum, int subcase, String reason) {
    tx.executeWithoutResult(
        status -> {
          Map<String, Object> job = jobs.lockById(jobId);
          executions.locked(executionId);
          if (!isCurrent(job, executionId)) return;
          var old = results.find(jobId, version);
          if (old.isPresent() && "COMPLETE".equals(old.get().get("status"))) {
            jobs.recordEvent(
                jobId,
                "RESULT_REPLAY_REJECTED",
                Map.of(
                    "executionId",
                    executionId,
                    "parserVersion",
                    version,
                    "reason",
                    bounded(reason)));
            return;
          }
          results.failed(
              jobId,
              executionId,
              version,
              job.get("analysisType").toString(),
              job.get("conditionCode").toString(),
              subcase,
              checksum,
              reason);
          jobs.recordEvent(
              jobId,
              "RESULT_PARSE_FAILED",
              Map.of(
                  "executionId", executionId, "parserVersion", version, "reason", bounded(reason)));
          if ("POST_PROCESSING".equals(job.get("status"))
              && pipelineBinding.canFailParse(
                  job, SqlRows.id(results.find(jobId, version).orElseThrow()))) {
            job =
                jobs.patch(
                    job,
                    Map.of(
                        "failureCode", "POST_PROCESS_FAILED", "failureMessage", bounded(reason)));
            jobs.transition(
                job,
                JobStatus.POST_PROCESS_FAILED,
                "POST_PROCESS_FAILED",
                Map.of("stage", "RESULT_PARSE", "parserVersion", version));
          }
        });
  }

  private static void requireSameSource(
      Map<String, Object> record, long executionId, String checksum) {
    if (((Number) record.get("executionId")).longValue() != executionId
        || !record.get("rawChecksum").equals(checksum))
      throw new IllegalArgumentException(
          "Parser version already belongs to different immutable output");
  }

  private static boolean isCurrent(Map<String, Object> job, long executionId) {
    return job.get("currentExecutionId") instanceof Number current
        && current.longValue() == executionId;
  }

  private Map<String, Object> repairKnownSuccess(
      Map<String, Object> job, Map<String, Object> execution) {
    if ("CANCEL_REQUESTED".equals(job.get("status")) && "SUCCESS".equals(execution.get("status"))) {
      // This is durable local evidence, never a new poll or submit. During commit the existing
      // Job -> Execution locks and REQUIRED transaction are reused by the common state applier.
      remoteStates.applyStored(SqlRows.id(execution), "PARSER_DURABLE_TERMINAL_REPAIR");
      return jobs.findById(SqlRows.id(job));
    }
    return job;
  }

  private static boolean parseStage(Map<String, Object> job) {
    return Set.of("POST_PROCESSING", "POST_PROCESS_FAILED", "REPORTING", "REPORT_FAILED", "SUCCESS")
        .contains(job.get("status"));
  }

  private static Map<String, Object> identity(
      Map<String, Object> job, Map<String, Object> execution, int subcase, String checksum) {
    return Map.of(
        "jobNo",
        job.get("jobNo"),
        "executionId",
        SqlRows.id(execution),
        "executionRequestId",
        execution.get("submitRequestId"),
        "analysisType",
        job.get("analysisType"),
        "conditionCode",
        job.get("conditionCode"),
        "subcase",
        subcase,
        "rawChecksum",
        checksum);
  }

  private FileDescriptor filesDescriptor(JsonDocument document) {
    return new FileDescriptor(
        files.root().relativize(document.path()).toString().replace('\\', '/'),
        document.path().getFileName().toString(),
        document.sizeBytes(),
        document.checksum());
  }

  private static String csv(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String bounded(String value) {
    if (value == null) return "Result parsing failed";
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private static void requireEqual(Object expected, Object actual, String field) {
    if (!equivalent(expected, actual)) throw new IllegalArgumentException(field + " mismatch");
  }

  private static boolean equivalent(Object left, Object right) {
    if (left instanceof Number a && right instanceof Number b)
      return Double.isFinite(a.doubleValue())
          && Double.isFinite(b.doubleValue())
          && new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
    if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b)
      return a.keySet().equals(b.keySet())
          && a.entrySet().stream()
              .allMatch(entry -> equivalent(entry.getValue(), b.get(entry.getKey())));
    return Objects.equals(left, right);
  }

  public record ParseOutcome(boolean applied, boolean stale, Map<String, Object> record) {}

  private record Prepared(
      ParseContext context, JsonDocument raw, FileDescriptor log, Path jobRoot) {}

  private record ArtifactSpec(String type, FileDescriptor file, String contentType) {}
}
