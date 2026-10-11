package com.example.cae.job.submission;

import com.example.cae.agent.AgentException;
import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.monitoring.RemoteExecutionStateService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobCancellationService {
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final ProjectAccess access;
  private final ComputeAgentClient agents;
  private final RemoteExecutionStateService states;
  private final TransactionTemplate tx;

  public JobCancellationService(
      JobRepository jobs,
      ExecutionRepository executions,
      ProjectAccess access,
      ComputeAgentClient agents,
      RemoteExecutionStateService states,
      TransactionTemplate tx) {
    this.jobs = jobs;
    this.executions = executions;
    this.access = access;
    this.agents = agents;
    this.states = states;
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
    if (job.get("currentExecutionId") == null) return;
    long id = ((Number) job.get("currentExecutionId")).longValue();
    Map<String, Object> execution = executions.find(id);
    if (!"CANCEL_REQUESTED".equals(job.get("status"))
        && !("UNKNOWN".equals(job.get("status"))
            && RemoteExecutionStateService.cancellationRequested(execution))) return;
    if (RemoteExecutionStateService.TERMINAL.contains(execution.get("status"))) {
      states.applyStored(id, "CANCEL_DURABLE_CONFIRMATION");
      return;
    }
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
      states.apply(id, execution.get("nodeId").toString(), remote, "CANCEL_CONFIRMATION");
    } catch (AgentException | ApiException failure) {
      tx.executeWithoutResult(
          status -> {
            Map<String, Object> current = jobs.lockById(jobId);
            if (current.get("currentExecutionId") instanceof Number currentId
                && currentId.longValue() == id
                && ("CANCEL_REQUESTED".equals(current.get("status"))
                    || "UNKNOWN".equals(current.get("status"))))
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
  }
}
