package com.example.cae.job.submission;

import com.example.cae.agent.AgentException;
import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.resource.ResourceReservationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExecutionDispatchService {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutionDispatchService.class);
  private final ExecutionRepository executions;
  private final JobRepository jobs;
  private final ComputeAgentClient agents;
  private final ResourceReservationService resources;
  private final TransactionTemplate tx;

  public ExecutionDispatchService(
      ExecutionRepository executions,
      JobRepository jobs,
      ComputeAgentClient agents,
      ResourceReservationService resources,
      TransactionTemplate tx) {
    this.executions = executions;
    this.jobs = jobs;
    this.agents = agents;
    this.resources = resources;
    this.tx = tx;
  }

  /**
   * Retrying transport always reuses the durable attempt request, never a fresh process identity.
   */
  public void submit(long executionId) {
    Map<String, Object> execution = executions.find(executionId);
    if (!"SUBMITTING".equals(execution.get("status"))) return;
    Map<String, Object> request = (Map<String, Object>) execution.get("request");
    LOG.info(
        "Agent submit jobNo={} executionId={} executionRequestId={} nodeId={}",
        request.get("jobNo"),
        executionId,
        execution.get("submitRequestId"),
        execution.get("nodeId"));
    try {
      Map<String, Object> accepted = agents.submit(execution.get("nodeId").toString(), request);
      tx.executeWithoutResult(
          status -> {
            Map<String, Object> job = jobs.lockById(((Number) execution.get("jobId")).longValue());
            Map<String, Object> current = executions.locked(executionId);
            if (!"SUBMITTING".equals(current.get("status"))) return;
            Timestamp now = Timestamp.from(Instant.now());
            boolean unknown = "UNKNOWN".equals(accepted.get("state"));
            current =
                executions.patch(
                    current,
                    Map.of(
                        "externalExecutionId",
                        accepted.get("externalExecutionId"),
                        "status",
                        unknown ? "UNKNOWN" : "SUBMITTED",
                        "submittedAt",
                        now));
            resources.accepted(((Number) current.get("reservationId")).longValue());
            if ("SUBMITTING".equals(job.get("status"))) {
              Map<String, Object> fields = new LinkedHashMap<>();
              fields.put("submittedAt", now);
              fields.put("failureCode", null);
              fields.put("failureMessage", null);
              job = jobs.patch(job, fields);
              jobs.transition(
                  job,
                  unknown ? JobStatus.UNKNOWN : JobStatus.SUBMITTED,
                  "AGENT_ACCEPTED",
                  Map.of(
                      "executionId",
                      executionId,
                      "externalExecutionId",
                      accepted.get("externalExecutionId"),
                      "remoteState",
                      accepted.get("state")));
            }
          });
      // A cancellation may have won while the HTTP acceptance was in flight.
      Map<String, Object> latest = executions.find(executionId);
      if (Boolean.TRUE.equals(latest.get("cancelRequested"))
          || Integer.valueOf(1).equals(latest.get("cancelRequested")))
        agents.cancel(
            latest.get("nodeId").toString(), latest.get("externalExecutionId").toString());
    } catch (AgentException failure) {
      tx.executeWithoutResult(
          status -> {
            Map<String, Object> job = jobs.lockById(((Number) execution.get("jobId")).longValue());
            Map<String, Object> current = executions.locked(executionId);
            if (!"SUBMITTING".equals(current.get("status"))) return;
            job =
                jobs.patch(
                    job,
                    Map.of(
                        "failureCode",
                        failure.code().name(),
                        "failureMessage",
                        failure.getMessage()));
            if (failure.ambiguous()) {
              jobs.recordEvent(
                  SqlRows.id(job),
                  "AGENT_SUBMIT_UNCERTAIN",
                  Map.of(
                      "executionId",
                      executionId,
                      "failureCode",
                      failure.code().name(),
                      "retryable",
                      true));
              return;
            }
            executions.patch(
                current,
                Map.of(
                    "status",
                    "SUBMIT_FAILED",
                    "finishedAt",
                    Timestamp.from(Instant.now()),
                    "rawMessage",
                    failure.getMessage()));
            resources.releaseByExecution(executionId);
            if ("CANCEL_REQUESTED".equals(job.get("status")))
              jobs.transition(
                  job, JobStatus.CANCELLED, "JOB_CANCELLED", Map.of("confirmedNotAccepted", true));
            else if ("SUBMITTING".equals(job.get("status")))
              jobs.transition(
                  job,
                  JobStatus.SUBMIT_FAILED,
                  "AGENT_REJECTED",
                  Map.of(
                      "executionId",
                      executionId,
                      "httpStatus",
                      failure.httpStatus(),
                      "failureCode",
                      failure.code().name()));
          });
    }
  }
}
