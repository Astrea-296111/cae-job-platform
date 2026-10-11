package com.example.cae.job.submission;

import com.example.cae.agent.AgentException;
import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.execution.RemoteExecutionFields;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.resource.ResourceReservationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobCancellationService {
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final ProjectAccess access;
  private final ComputeAgentClient agents;
  private final ResourceReservationService resources;
  private final TransactionTemplate tx;

  public JobCancellationService(
      JobRepository jobs,
      ExecutionRepository executions,
      ProjectAccess access,
      ComputeAgentClient agents,
      ResourceReservationService resources,
      TransactionTemplate tx) {
    this.jobs = jobs;
    this.executions = executions;
    this.access = access;
    this.agents = agents;
    this.resources = resources;
    this.tx = tx;
  }

  public Map<String, Object> cancel(Authentication user, String jobNo) {
    Map<String, Object> requested = jobs.findByNo(jobNo);
    access.requireOwner(user, requested);
    Map<String, Object> result =
        tx.execute(
            status -> {
              Map<String, Object> current = jobs.lockById(SqlRows.id(requested));
              if (JobStatus.valueOf(current.get("status").toString()).terminal()) return current;
              if (!"CANCEL_REQUESTED".equals(current.get("status"))) {
                current =
                    jobs.transition(
                        current,
                        JobStatus.CANCEL_REQUESTED,
                        "CANCEL_REQUESTED",
                        Map.of("actor", user.getName()));
                jobs.audit(
                    user.getName(),
                    "JOB_CANCEL",
                    "JOB",
                    jobNo,
                    Map.of("status", requested.get("status")),
                    Map.of("status", "CANCEL_REQUESTED"));
              }
              Object executionId = current.get("currentExecutionId");
              if (executionId == null) return finishWithoutProcess(current);
              Map<String, Object> execution = executions.locked(((Number) executionId).longValue());
              if (Set.of("SUBMIT_FAILED", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED")
                  .contains(execution.get("status"))) return finishWithoutProcess(current);
              if (!Boolean.TRUE.equals(execution.get("cancelRequested")))
                executions.patch(execution, Map.of("cancelRequested", true));
              return current;
            });
    if ("CANCEL_REQUESTED".equals(result.get("status"))) continueCancel(SqlRows.id(result));
    return jobs.findById(SqlRows.id(result));
  }

  private Map<String, Object> finishWithoutProcess(Map<String, Object> job) {
    return jobs.transition(
        jobs.patch(job, Map.of("finishedAt", Timestamp.from(Instant.now()))),
        JobStatus.CANCELLED,
        "JOB_CANCELLED",
        Map.of("noActiveProcess", true));
  }

  public void continueCancel(long jobId) {
    Map<String, Object> job = jobs.findById(jobId);
    if (!"CANCEL_REQUESTED".equals(job.get("status")) || job.get("currentExecutionId") == null)
      return;
    long id = ((Number) job.get("currentExecutionId")).longValue();
    Map<String, Object> execution = executions.find(id);
    Map<String, Object> remote;
    try {
      if (execution.get("externalExecutionId") == null) {
        Map<String, Object> found =
            agents.queryByRequest(
                execution.get("nodeId").toString(), execution.get("submitRequestId").toString());
        remote =
            agents.cancel(
                execution.get("nodeId").toString(), found.get("externalExecutionId").toString());
      } else
        remote =
            agents.cancel(
                execution.get("nodeId").toString(),
                execution.get("externalExecutionId").toString());
    } catch (AgentException failure) {
      tx.executeWithoutResult(
          status -> {
            Map<String, Object> current = jobs.lockById(jobId);
            if ("CANCEL_REQUESTED".equals(current.get("status")))
              jobs.patch(
                  current,
                  Map.of(
                      "failureCode",
                      "CANCEL_FAILED",
                      "failureMessage",
                      "Cancellation awaits a confirmed remote state"));
          });
      return;
    }
    String state = remote.get("state").toString();
    if (!Set.of("SUCCESS", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED").contains(state)) return;
    Map<String, Object> facts = RemoteExecutionFields.from(remote);
    tx.executeWithoutResult(
        status -> {
          Map<String, Object> current = jobs.lockById(jobId);
          Map<String, Object> attempt = executions.locked(id);
          if (!"CANCEL_REQUESTED".equals(current.get("status"))) return;
          Map<String, Object> fields = new LinkedHashMap<>(facts);
          fields.put("externalExecutionId", remote.get("externalExecutionId"));
          fields.put("status", state);
          fields.putIfAbsent("finishedAt", Timestamp.from(Instant.now()));
          executions.patch(attempt, fields);
          resources.releaseByExecution(id);
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("finishedAt", fields.get("finishedAt"));
          metadata.put("rawSolverCode", remote.get("rawSolverCode"));
          metadata.put("rawMessage", remote.get("rawMessage"));
          metadata.put(
              "failureCode",
              state.equals("RUNTIME_FAILED")
                  ? "SOLVER_RUNTIME_FAILED"
                  : state.equals("TIMEOUT") ? "SOLVER_TIMEOUT" : null);
          metadata.put(
              "failureMessage",
              state.equals("RUNTIME_FAILED") || state.equals("TIMEOUT")
                  ? remote.get("rawMessage")
                  : null);
          current = jobs.patch(current, metadata);
          jobs.transition(
              current,
              state.equals("SUCCESS")
                  ? JobStatus.POST_PROCESSING
                  : state.equals("CANCELLED") ? JobStatus.CANCELLED : JobStatus.RUNTIME_FAILED,
              state.equals("SUCCESS") ? "SOLVER_COMPLETION_WON_CANCEL" : "CANCEL_REMOTE_CONFIRMED",
              Map.of("executionId", id, "remoteState", state));
        });
  }
}
