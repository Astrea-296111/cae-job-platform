package com.example.cae.monitoring;

import com.example.cae.agent.AgentException;
import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.common.ApiException;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.submission.JobCancellationService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciliation observes the durable attempt; it never allocates or submits a solver. */
@Component
public class ExecutionMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutionMonitor.class);
  private final ExecutionRepository executions;
  private final ComputeAgentClient agents;
  private final RemoteExecutionStateService states;
  private final ExecutionPollLeaseRepository leases;
  private final JobCancellationService cancellation;
  private final boolean enabled;
  private final Duration pollDelay;
  private final Duration leaseDuration;
  private final String owner = "monitor-" + UUID.randomUUID();
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

  public ExecutionMonitor(
      ExecutionRepository executions,
      ComputeAgentClient agents,
      RemoteExecutionStateService states,
      ExecutionPollLeaseRepository leases,
      JobCancellationService cancellation,
      @Value("${cae.background.enabled:true}") boolean enabled,
      @Value("${cae.monitor.poll-ms:5000}") long pollMs,
      @Value("${cae.monitor.lease-ms:15000}") long leaseMs) {
    if (pollMs < 100 || leaseMs < 1000)
      throw new IllegalArgumentException("Monitoring intervals are invalid");
    this.executions = executions;
    this.agents = agents;
    this.states = states;
    this.leases = leases;
    this.cancellation = cancellation;
    this.enabled = enabled;
    this.pollDelay = Duration.ofMillis(pollMs);
    this.leaseDuration = Duration.ofMillis(leaseMs);
  }

  @Scheduled(fixedDelayString = "${cae.monitor.scan-ms:5000}")
  public void scan() {
    if (!enabled || inFlight.size() >= 12) return;
    for (ExecutionPollLeaseRepository.PollClaim claim :
        leases.claim(owner, 12 - inFlight.size(), leaseDuration, List.of())) {
      if (!inFlight.add(claim.executionId())) {
        leases.finish(claim, pollDelay, null);
        continue;
      }
      workers.execute(
          () -> {
            try {
              reconcile(claim);
            } finally {
              inFlight.remove(claim.executionId());
            }
          });
    }
  }

  /** Scoped synchronous reconciliation is also useful for deterministic recovery verification. */
  public void reconcileExecution(long executionId) {
    leases.makeDue(executionId);
    for (ExecutionPollLeaseRepository.PollClaim claim :
        leases.claim(owner, 1, leaseDuration, List.of(executionId))) reconcile(claim);
  }

  private void reconcile(ExecutionPollLeaseRepository.PollClaim claim) {
    String failure = null;
    try {
      Map<String, Object> execution = executions.find(claim.executionId());
      if (RemoteExecutionStateService.TERMINAL.contains(execution.get("status"))) {
        states.applyStoredPolled(claim);
        return;
      }
      String nodeId = execution.get("nodeId").toString();
      if (states.nodeOffline(nodeId)) {
        failure = "Node heartbeat is unavailable; capacity is retained";
        states.unknown(claim.executionId(), "NODE_OFFLINE", failure, claim);
        return;
      }
      Map<String, Object> remote =
          execution.get("externalExecutionId") == null
              ? agents.queryByRequest(nodeId, execution.get("submitRequestId").toString())
              : agents.query(nodeId, execution.get("externalExecutionId").toString());
      states.applyPolled(claim, remote);
      checkWalltime(execution, remote);
      if (RemoteExecutionStateService.cancellationRequested(executions.find(claim.executionId()))
          && !RemoteExecutionStateService.TERMINAL.contains(remote.get("state")))
        cancellation.continueCancel(claim.jobId());
    } catch (AgentException error) {
      failure =
          error.notFound()
              ? "Agent has no matching execution; absence does not prove the solver stopped"
              : error.getMessage();
      states.unknown(
          claim.executionId(),
          error.notFound() ? "UNKNOWN_REMOTE_STATE" : error.code().name(),
          failure,
          claim);
    } catch (ApiException error) {
      failure = "Remote execution observation could not be validated: " + error.code();
      states.unknown(claim.executionId(), "UNKNOWN_REMOTE_STATE", failure, claim);
    } catch (RuntimeException error) {
      failure = "Reconciliation deferred: " + error.getClass().getSimpleName();
      LOG.warn(
          "Execution reconciliation deferred executionId={} cause={}",
          claim.executionId(),
          error.getClass().getSimpleName());
    } finally {
      leases.finish(claim, pollDelay, failure);
    }
  }

  private static void checkWalltime(Map<String, Object> execution, Map<String, Object> remote) {
    if (!"RUNNING".equals(remote.get("state")) || remote.get("startedAt") == null) return;
    Map<String, Object> request = (Map<String, Object>) execution.get("request");
    Map<String, Object> resource = (Map<String, Object>) request.get("resource");
    double minutes = ((Number) resource.get("walltimeMin")).doubleValue();
    if (Instant.parse(remote.get("startedAt").toString())
        .plusMillis((long) (minutes * 60_000))
        .isBefore(Instant.now()))
      LOG.warn(
          "Remote execution exceeds requested walltime executionId={} requestId={}; awaiting confirmed terminal evidence",
          execution.get("id"),
          execution.get("submitRequestId"));
  }

  @PreDestroy
  public void close() {
    workers.shutdownNow();
  }
}
