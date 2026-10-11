package com.example.cae.monitoring;

import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.execution.RemoteExecutionFields;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.resource.NodeRegistryService;
import com.example.cae.resource.ResourceReservationService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** One identity-checked, monotonic transaction for callback, poll and cancellation observations. */
@Service
public class RemoteExecutionStateService {
  public static final Set<String> TERMINAL =
      Set.of("SUCCESS", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED", "SUBMIT_FAILED");
  private static final Set<String> WIRE_STATES =
      Set.of(
          "SUBMITTED", "RUNNING", "SUCCESS", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED", "UNKNOWN");
  private static final Set<String> ACTIVE_JOBS =
      Set.of("SUBMITTING", "SUBMITTED", "RUNNING", "UNKNOWN", "CANCEL_REQUESTED");
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final ComputeAgentClient agents;
  private final NodeRegistryService nodes;
  private final ResourceReservationService resources;
  private final ExecutionPollLeaseRepository leases;
  private final TransactionTemplate tx;
  private final long offlineSeconds;

  public RemoteExecutionStateService(
      JobRepository jobs,
      ExecutionRepository executions,
      ComputeAgentClient agents,
      NodeRegistryService nodes,
      ResourceReservationService resources,
      ExecutionPollLeaseRepository leases,
      TransactionTemplate tx,
      @Value("${cae.nodes.offline-seconds:20}") long offlineSeconds) {
    this.jobs = jobs;
    this.executions = executions;
    this.agents = agents;
    this.nodes = nodes;
    this.resources = resources;
    this.leases = leases;
    this.tx = tx;
    this.offlineSeconds = offlineSeconds;
  }

  public ApplyResult callback(String nodeId, Map<String, Object> remote) {
    validate(remote);
    Map<String, Object> execution =
        executions.findByRequest(remote.get("executionRequestId").toString());
    return apply(SqlRows.id(execution), nodeId, remote, "CALLBACK");
  }

  public ApplyResult apply(
      long executionId, String nodeId, Map<String, Object> remote, String source) {
    validate(remote);
    return observe(executionId, nodeId, remote, source, null, false);
  }

  public ApplyResult applyPolled(
      ExecutionPollLeaseRepository.PollClaim claim, Map<String, Object> remote) {
    validate(remote);
    return observe(
        claim.executionId(),
        executions.find(claim.executionId()).get("nodeId").toString(),
        remote,
        "POLL",
        claim,
        false);
  }

  /** Durable terminal evidence also repairs pre-monitoring completion and reservation windows. */
  public ApplyResult applyStoredPolled(ExecutionPollLeaseRepository.PollClaim claim) {
    return applyStored(claim.executionId(), "DURABLE_TERMINAL_REPAIR", claim);
  }

  public ApplyResult applyStored(long executionId, String source) {
    return applyStored(executionId, source, null);
  }

  private ApplyResult applyStored(
      long executionId, String source, ExecutionPollLeaseRepository.PollClaim claim) {
    Map<String, Object> execution = executions.find(executionId);
    if (!TERMINAL.contains(execution.get("status")))
      return result(false, execution, jobs.findById(((Number) execution.get("jobId")).longValue()));
    Map<String, Object> remote = new LinkedHashMap<>();
    remote.put("executionRequestId", execution.get("submitRequestId"));
    remote.put("externalExecutionId", execution.get("externalExecutionId"));
    remote.put("state", execution.get("status"));
    for (String key :
        new String[] {
          "progress",
          "pid",
          "processIdentity",
          "exitCode",
          "rawSolverCode",
          "rawMessage",
          "startedAt",
          "finishedAt"
        }) remote.put(key, execution.get(key));
    return observe(executionId, execution.get("nodeId").toString(), remote, source, claim, true);
  }

  public boolean nodeOffline(String nodeId) {
    Map<String, Object> node = nodes.find(nodeId);
    Object heartbeat = node.get("lastHeartbeat");
    return "OFFLINE".equals(node.get("status"))
        || heartbeat == null
        || Instant.parse(heartbeat.toString()).isBefore(Instant.now().minusSeconds(offlineSeconds));
  }

  public ApplyResult unknown(
      long executionId,
      String failureCode,
      String reason,
      ExecutionPollLeaseRepository.PollClaim claim) {
    Map<String, Object> snapshot = executions.find(executionId);
    return tx.execute(
        status -> {
          Map<String, Object> job = jobs.lockById(((Number) snapshot.get("jobId")).longValue());
          Map<String, Object> execution = executions.locked(executionId);
          if ((claim != null && !leases.owns(claim))
              || !isCurrent(job, executionId)
              || TERMINAL.contains(execution.get("status"))
              || !ACTIVE_JOBS.contains(job.get("status"))) return result(false, execution, job);
          return markUnknown(job, execution, failureCode, reason);
        });
  }

  private ApplyResult observe(
      long executionId,
      String nodeId,
      Map<String, Object> remote,
      String source,
      ExecutionPollLeaseRepository.PollClaim claim,
      boolean stored) {
    if (!agents.nodes().containsKey(nodeId))
      throw ApiException.invalid("Execution node must be configured");
    Map<String, Object> snapshot = executions.find(executionId);
    return tx.execute(
        status -> {
          Map<String, Object> job = jobs.lockById(((Number) snapshot.get("jobId")).longValue());
          Map<String, Object> execution = executions.locked(executionId);
          if (claim != null && !leases.owns(claim)) return result(false, execution, job);
          identity(execution, nodeId, remote, stored);
          String state = remote.get("state").toString();
          if (TERMINAL.contains(execution.get("status")) && !execution.get("status").equals(state))
            return result(false, execution, job);
          if (!isCurrent(job, executionId)) {
            // A late event can repair a proven terminal old reservation, never mutate the new
            // attempt.
            boolean released =
                TERMINAL.contains(execution.get("status"))
                    && resources.releaseByExecution(executionId);
            return result(released, execution, job);
          }
          if (TERMINAL.contains(state)) return terminal(job, execution, remote, source);
          if (JobStatus.valueOf(job.get("status").toString()).terminal()
              || !ACTIVE_JOBS.contains(job.get("status"))) return result(false, execution, job);
          if ("UNKNOWN".equals(state) || nodeOffline(nodeId)) {
            Map<String, Object> facts = facts(execution, remote);
            facts.put("externalExecutionId", remote.get("externalExecutionId"));
            execution = patchExecution(execution, facts);
            resources.accepted(((Number) execution.get("reservationId")).longValue());
            return markUnknown(
                job,
                execution,
                nodeOffline(nodeId) ? "NODE_OFFLINE" : "UNKNOWN_REMOTE_STATE",
                nodeOffline(nodeId)
                    ? "Node heartbeat is unavailable; remote capacity is retained"
                    : "Agent cannot establish a confirmed execution state");
          }
          Map<String, Object> fields = facts(execution, remote);
          fields.put("externalExecutionId", remote.get("externalExecutionId"));
          fields.put(
              "status",
              "RUNNING".equals(execution.get("status"))
                      || execution.get("runningObservedAt") != null
                  ? "RUNNING"
                  : state);
          if (execution.get("submittedAt") == null)
            fields.put("submittedAt", Timestamp.from(Instant.now()));
          Object oldVersion = execution.get("version");
          execution = patchExecution(execution, fields);
          boolean applied = !oldVersion.equals(execution.get("version"));
          applied |= resources.accepted(((Number) execution.get("reservationId")).longValue());
          Map<String, Object> metadata = new LinkedHashMap<>();
          if (job.get("submittedAt") == null)
            metadata.put(
                "submittedAt",
                Timestamp.from(Instant.parse(execution.get("submittedAt").toString())));
          if (execution.get("startedAt") != null && job.get("startedAt") == null)
            metadata.put(
                "startedAt", Timestamp.from(Instant.parse(execution.get("startedAt").toString())));
          boolean cancel =
              cancellationRequested(execution) || "CANCEL_REQUESTED".equals(job.get("status"));
          if (!cancel) {
            metadata.put("failureCode", null);
            metadata.put("failureMessage", null);
          }
          Object jobVersion = job.get("version");
          job = patchJob(job, metadata);
          applied |= !jobVersion.equals(job.get("version"));
          if (cancel) {
            if (!"CANCEL_REQUESTED".equals(job.get("status"))) {
              job =
                  jobs.transition(
                      job,
                      JobStatus.CANCEL_REQUESTED,
                      "CANCELLATION_INTENT_RECOVERED",
                      detail(executionId, state, source));
              applied = true;
            }
          } else {
            if ("SUBMITTING".equals(job.get("status")) || "UNKNOWN".equals(job.get("status"))) {
              job =
                  jobs.transition(
                      job,
                      JobStatus.SUBMITTED,
                      "REMOTE_ACCEPTANCE_RECONCILED",
                      detail(executionId, state, source));
              applied = true;
            }
            if ("RUNNING".equals(execution.get("status"))
                && "SUBMITTED".equals(job.get("status"))) {
              job =
                  jobs.transition(
                      job, JobStatus.RUNNING, "SOLVER_RUNNING", detail(executionId, state, source));
              applied = true;
            }
          }
          return result(applied, execution, job);
        });
  }

  private ApplyResult markUnknown(
      Map<String, Object> job, Map<String, Object> execution, String failureCode, String reason) {
    long id = SqlRows.id(execution);
    Object executionVersion = execution.get("version"), jobVersion = job.get("version");
    execution = patchExecution(execution, Map.of("status", "UNKNOWN"));
    String bounded = reason.substring(0, Math.min(1000, reason.length()));
    Map<String, Object> metadata = new LinkedHashMap<>();
    // Cancellation failure remains inspectable while the independent offline state is reconciled.
    if (!"CANCEL_FAILED".equals(job.get("failureCode"))) {
      metadata.put("failureCode", failureCode);
      metadata.put("failureMessage", bounded);
    }
    job = patchJob(job, metadata);
    boolean applied =
        !executionVersion.equals(execution.get("version"))
            || !jobVersion.equals(job.get("version"));
    if (!"UNKNOWN".equals(job.get("status"))) {
      job =
          jobs.transition(
              job,
              JobStatus.UNKNOWN,
              "REMOTE_STATE_UNKNOWN",
              Map.of("executionId", id, "failureCode", failureCode, "resourcesRetained", true));
      applied = true;
    }
    return result(applied, execution, job);
  }

  private ApplyResult terminal(
      Map<String, Object> job,
      Map<String, Object> execution,
      Map<String, Object> remote,
      String source) {
    long id = SqlRows.id(execution);
    String state = remote.get("state").toString();
    Map<String, Object> fields = facts(execution, remote);
    fields.put("status", state);
    if (remote.get("externalExecutionId") != null)
      fields.put("externalExecutionId", remote.get("externalExecutionId"));
    if (execution.get("finishedAt") == null && !fields.containsKey("finishedAt"))
      fields.put("finishedAt", Timestamp.from(Instant.now()));
    if (execution.get("terminalAppliedAt") == null)
      fields.put("terminalAppliedAt", Timestamp.from(Instant.now()));
    Object version = execution.get("version");
    execution = patchExecution(execution, fields);
    boolean applied = !version.equals(execution.get("version"));
    applied |= resources.releaseByExecution(id);
    if (JobStatus.valueOf(job.get("status").toString()).terminal())
      return result(applied, execution, job);
    if (!ACTIVE_JOBS.contains(job.get("status")) && !"POST_PROCESSING".equals(job.get("status")))
      return result(applied, execution, job);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("rawSolverCode", execution.get("rawSolverCode"));
    metadata.put("rawMessage", execution.get("rawMessage"));
    metadata.put(
        "finishedAt",
        "SUCCESS".equals(state)
            ? null
            : Timestamp.from(Instant.parse(execution.get("finishedAt").toString())));
    if (execution.get("startedAt") != null && job.get("startedAt") == null)
      metadata.put(
          "startedAt", Timestamp.from(Instant.parse(execution.get("startedAt").toString())));
    String failure =
        switch (state) {
          case "RUNTIME_FAILED" -> "SOLVER_RUNTIME_FAILED";
          case "TIMEOUT" -> "SOLVER_TIMEOUT";
          case "SUBMIT_FAILED" -> "SUBMIT_REJECTED";
          default -> null;
        };
    metadata.put("failureCode", failure);
    metadata.put("failureMessage", failure == null ? null : execution.get("rawMessage"));
    Object jobVersion = job.get("version");
    job = patchJob(job, metadata);
    applied |= !jobVersion.equals(job.get("version"));
    if ("SUBMITTING".equals(job.get("status")) && !"SUBMIT_FAILED".equals(state)) {
      job =
          jobs.transition(
              job, JobStatus.SUBMITTED, "REMOTE_ACCEPTANCE_RECONCILED", detail(id, state, source));
      applied = true;
    }
    if ("CANCELLED".equals(state)
        && !Set.of("CANCEL_REQUESTED", "UNKNOWN").contains(job.get("status"))) {
      job =
          jobs.transition(
              job,
              JobStatus.CANCEL_REQUESTED,
              "REMOTE_CANCELLATION_OBSERVED",
              detail(id, state, source));
      applied = true;
    }
    JobStatus target =
        switch (state) {
          case "SUCCESS" -> JobStatus.POST_PROCESSING;
          case "CANCELLED" -> JobStatus.CANCELLED;
          case "SUBMIT_FAILED" ->
              "CANCEL_REQUESTED".equals(job.get("status"))
                  ? JobStatus.CANCELLED
                  : JobStatus.SUBMIT_FAILED;
          default -> JobStatus.RUNTIME_FAILED;
        };
    if (!target.name().equals(job.get("status"))) {
      String event = "SUCCESS".equals(state) ? "SOLVER_SUCCESS" : "SOLVER_TERMINAL_CONFIRMED";
      job = jobs.transition(job, target, event, detail(id, state, source));
      applied = true;
    }
    if ("SUCCESS".equals(state) && execution.get("resultParseRequestedAt") == null) {
      executions.patch(execution, Map.of("resultParseRequestedAt", Timestamp.from(Instant.now())));
      jobs.recordEvent(
          SqlRows.id(job),
          "RESULT_PARSE_REQUESTED",
          Map.of(
              "executionId",
              id,
              "executionRequestId",
              execution.get("submitRequestId"),
              "source",
              source));
      applied = true;
    }
    return result(applied, execution, job);
  }

  private static Map<String, Object> facts(
      Map<String, Object> execution, Map<String, Object> remote) {
    Map<String, Object> result = RemoteExecutionFields.from(remote);
    result.values().removeIf(value -> value == null);
    // Persist knowledge independently of transient UNKNOWN and optional remote start facts.
    if ("RUNNING".equals(remote.get("state")) && execution.get("runningObservedAt") == null)
      result.put("runningObservedAt", Timestamp.from(Instant.now()));
    if (result.get("progress") instanceof Number progress) {
      double current = ((Number) execution.get("progress")).doubleValue();
      result.put("progress", BigDecimal.valueOf(Math.max(current, progress.doubleValue())));
    }
    // Start/finish facts are immutable after first confirmation.
    if (execution.get("startedAt") != null) result.remove("startedAt");
    if (execution.get("finishedAt") != null) result.remove("finishedAt");
    return result;
  }

  private Map<String, Object> patchExecution(
      Map<String, Object> current, Map<String, Object> fields) {
    Map<String, Object> changed = changed(current, fields);
    return changed.isEmpty() ? current : executions.patch(current, changed);
  }

  private Map<String, Object> patchJob(Map<String, Object> current, Map<String, Object> fields) {
    Map<String, Object> changed = changed(current, fields);
    return changed.isEmpty() ? current : jobs.patch(current, changed);
  }

  private static Map<String, Object> changed(
      Map<String, Object> current, Map<String, Object> fields) {
    Map<String, Object> changed = new LinkedHashMap<>();
    fields.forEach(
        (key, value) -> {
          Object old = current.get(key);
          boolean same = java.util.Objects.equals(old, value);
          if (old instanceof Number a && value instanceof Number b)
            same = new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
          if (old instanceof String timestamp && value instanceof Timestamp time)
            same = timestamp.equals(time.toInstant().toString());
          if (!same) changed.put(key, value);
        });
    return changed;
  }

  public static boolean cancellationRequested(Map<String, Object> execution) {
    Object value = execution.get("cancelRequested");
    return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() == 1);
  }

  private static boolean isCurrent(Map<String, Object> job, long id) {
    return job.get("currentExecutionId") instanceof Number current && current.longValue() == id;
  }

  private static void identity(
      Map<String, Object> execution, String nodeId, Map<String, Object> remote, boolean stored) {
    if (!nodeId.equals(execution.get("nodeId"))
        || !execution.get("submitRequestId").equals(remote.get("executionRequestId"))
        || (execution.get("externalExecutionId") != null
            && !execution.get("externalExecutionId").equals(remote.get("externalExecutionId"))))
      throw ApiException.conflict("Remote execution identity does not match the durable attempt");
    if (!stored
        && execution.get("processIdentity") != null
        && remote.get("processIdentity") != null
        && !execution.get("processIdentity").equals(remote.get("processIdentity")))
      throw ApiException.conflict("Remote process identity changed for the same execution");
    if (!stored
        && execution.get("pid") instanceof Number expectedPid
        && remote.get("pid") instanceof Number observedPid
        && expectedPid.longValue() != observedPid.longValue())
      throw ApiException.conflict("Remote process ID changed for the same execution");
  }

  private static void validate(Map<String, Object> remote) {
    if (remote == null) throw ApiException.invalid("Execution observation is required");
    safeIdentifier(remote.get("executionRequestId"), 100);
    safeIdentifier(remote.get("externalExecutionId"), 128);
    if (!(remote.get("state") instanceof String state) || !WIRE_STATES.contains(state))
      throw ApiException.invalid("Execution state is invalid");
    for (String key : new String[] {"progress", "pid", "exitCode"}) {
      Object value = remote.get(key);
      if (value == null) continue;
      if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
        throw ApiException.invalid("Execution numeric fact is invalid");
      double numeric = number.doubleValue();
      if ((key.equals("progress") && (numeric < 0 || numeric > 100))
          || (key.equals("pid")
              && (numeric <= 0 || numeric > Long.MAX_VALUE || numeric != Math.rint(numeric)))
          || (key.equals("exitCode")
              && (numeric < Integer.MIN_VALUE
                  || numeric > Integer.MAX_VALUE
                  || numeric != Math.rint(numeric))))
        throw ApiException.invalid("Execution numeric fact is outside its valid range");
    }
    for (String key : new String[] {"processIdentity", "rawSolverCode", "rawMessage"}) {
      Object value = remote.get(key);
      if (value != null
          && (!(value instanceof String text)
              || text.length() > (key.equals("rawMessage") ? 2000 : 128)))
        throw ApiException.invalid("Execution text fact exceeds its valid range");
    }
    for (String key : new String[] {"startedAt", "finishedAt"}) {
      Object value = remote.get(key);
      if (value == null) continue;
      try {
        Instant.parse(value.toString());
      } catch (DateTimeParseException error) {
        throw ApiException.invalid("Execution timestamp must be an ISO-8601 instant");
      }
    }
  }

  private static void safeIdentifier(Object value, int maximum) {
    if (!(value instanceof String text)
        || text.length() > maximum
        || !text.matches("[A-Za-z0-9][A-Za-z0-9_-]*"))
      throw ApiException.invalid("Execution identity is invalid");
  }

  private static Map<String, Object> detail(long id, String state, String source) {
    return Map.of("executionId", id, "remoteState", state, "source", source);
  }

  private static ApplyResult result(
      boolean applied, Map<String, Object> execution, Map<String, Object> job) {
    return new ApplyResult(true, applied, SqlRows.id(execution), job.get("status").toString());
  }

  public record ApplyResult(
      boolean accepted, boolean applied, long executionId, String jobStatus) {}
}
