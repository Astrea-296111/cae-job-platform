package com.example.cae.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.job.submission.DispatchOutcome;
import com.example.cae.job.submission.ExecutionDispatchService;
import com.example.cae.job.submission.JobAllocationService;
import com.example.cae.job.submission.JobCancellationService;
import com.example.cae.resource.ResourceReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Real MySQL ledger and HTTP recovery: remote uncertainty never starts another Solver. */
@SpringBootTest
@AutoConfigureMockMvc
class ExecutionMonitoringIntegrationTest {
  private static final Path ROOT = temporaryRoot();
  private static final ControlledAgent AGENT = new ControlledAgent();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("CASE_ROOT", () -> ROOT.resolve("cases").toString());
    registry.add("DATA_ROOT", () -> ROOT.resolve("data").toString());
    registry.add("WORKER_URLS", () -> "node-a=" + AGENT.origin());
    registry.add("cae.agent.request-timeout-ms", () -> 1000);
    registry.add("cae.background.enabled", () -> false);
    registry.add("cae.mq.listeners-enabled", () -> false);
  }

  @Autowired MockMvc mvc;
  @Autowired JsonCodec json;
  @Autowired JdbcTemplate jdbc;
  @Autowired JobRepository jobs;
  @Autowired ExecutionRepository executions;
  @Autowired JobAllocationService allocation;
  @Autowired ExecutionDispatchService dispatch;
  @Autowired ResourceReservationService resources;
  @Autowired JobCancellationService cancellation;
  @Autowired RemoteExecutionStateService state;
  @Autowired ExecutionMonitor monitor;
  @Autowired ExecutionPollLeaseRepository leases;
  private final List<Long> ownedJobs = new ArrayList<>();
  private List<Map<String, Object>> originalNodes;

  @BeforeEach
  void freshNodeAndControlledAgent() {
    AGENT.reset();
    originalNodes = jdbc.query("SELECT * FROM cae_compute_node", SqlRows.MAPPER);
    jdbc.update("UPDATE cae_compute_node SET drained=TRUE");
    online();
  }

  @AfterEach
  void releaseOnlyOwnedReservationsAndRestoreNodes() {
    for (long jobId : ownedJobs)
      for (Map<String, Object> execution : executions.history(jobId))
        resources.releaseByExecution(SqlRows.id(execution));
    for (Map<String, Object> node : originalNodes)
      jdbc.update(
          "UPDATE cae_compute_node SET status=?,drained=?,workspace_reachable=?,"
              + "last_heartbeat=?,observed_cpu_used=?,observed_memory_used_gb=? WHERE node_id=?",
          node.get("status"),
          node.get("drained"),
          node.get("workspaceReachable"),
          node.get("lastHeartbeat") == null
              ? null
              : Timestamp.from(Instant.parse(node.get("lastHeartbeat").toString())),
          node.get("observedCpuUsed"),
          node.get("observedMemoryUsedGb"),
          node.get("nodeId"));
  }

  @AfterAll
  static void closeHttpAgent() {
    AGENT.close();
  }

  @Test
  void duplicateAndOutOfOrderCallbacksPreserveTerminalStateAndOneParseCommand() throws Exception {
    Fixture fixture = submitted();
    Map<String, Object> running = remote(fixture, "RUNNING", 80);
    state.callback("node-a", running);
    state.callback("node-a", remote(fixture, "SUBMITTED", 10));
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "RUNNING");
    assertThat(progress(fixture)).isEqualTo(80);

    ExecutorService callers = Executors.newFixedThreadPool(6);
    try {
      List<java.util.concurrent.Callable<Void>> callbacks = new ArrayList<>();
      for (int i = 0; i < 12; i++)
        callbacks.add(
            () -> {
              state.callback("node-a", remote(fixture, "SUCCESS", 100));
              return null;
            });
      for (var result : callers.invokeAll(callbacks)) result.get();
    } finally {
      callers.shutdownNow();
    }
    var duplicate = state.callback("node-a", remote(fixture, "SUCCESS", 100));
    assertThat(duplicate.accepted()).isTrue();
    assertThat(duplicate.applied()).isFalse();
    state.callback("node-a", running);
    state.callback("node-a", remote(fixture, "CANCELLED", 80));

    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(executions.find(fixture.executionId())).containsEntry("status", "SUCCESS");
    assertThat(progress(fixture)).isEqualTo(100);
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
    assertThat(jobEvents(fixture, "SOLVER_SUCCESS")).isEqualTo(1);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void callbackRejectsMismatchedIdentityBeforeChangingJobOrLedger() throws Exception {
    Fixture fixture = submitted();
    state.callback("node-a", remote(fixture, "SUBMITTED", 0));
    Map<String, Object> wrongRequest = new LinkedHashMap<>(remote(fixture, "SUCCESS", 100));
    wrongRequest.put("executionRequestId", "unknown-" + UUID.randomUUID());
    assertThatThrownBy(() -> state.callback("node-a", wrongRequest))
        .isInstanceOf(ApiException.class);
    Map<String, Object> mismatchedExternal = new LinkedHashMap<>(remote(fixture, "SUCCESS", 100));
    mismatchedExternal.put("externalExecutionId", "wrong-external");
    assertThatThrownBy(() -> state.callback("node-a", mismatchedExternal))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                state.apply(
                    fixture.executionId(), "node-b", remote(fixture, "SUCCESS", 100), "TEST"))
        .isInstanceOf(ApiException.class);
    Map<String, Object> mismatchedProcess = new LinkedHashMap<>(remote(fixture, "SUCCESS", 100));
    mismatchedProcess.put("processIdentity", "4242:reused-process");
    assertThatThrownBy(() -> state.callback("node-a", mismatchedProcess))
        .isInstanceOf(ApiException.class);
    Map<String, Object> mismatchedPid = new LinkedHashMap<>(remote(fixture, "SUCCESS", 100));
    mismatchedPid.put("pid", 4243);
    assertThatThrownBy(() -> state.callback("node-a", mismatchedPid))
        .isInstanceOf(ApiException.class);
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUBMITTED");
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(parseCommands(fixture)).isZero();
  }

  @Test
  void internalCallbackRequiresServiceTokenAndIgnoresRemoteFilesystemPath() throws Exception {
    Fixture fixture = submitted();
    Map<String, Object> payload = new LinkedHashMap<>(remote(fixture, "SUCCESS", 100));
    payload.put("workspace", "/tmp/untrusted-path");
    String originalWorkspace = executions.find(fixture.executionId()).get("workspace").toString();
    mvc.perform(
            post("/internal/v1/agents/node-a/executions/callback")
                .contentType("application/json")
                .content(json.write(payload)))
        .andExpect(status().isUnauthorized());
    assertThat(parseCommands(fixture)).isZero();
    mvc.perform(
            post("/internal/v1/agents/node-a/executions/callback")
                .header("X-Service-Token", "demo-service-token")
                .contentType("application/json")
                .content(json.write(payload)))
        .andExpect(status().isOk());
    assertThat(executions.find(fixture.executionId()))
        .containsEntry("workspace", originalWorkspace);
    assertThat(parseCommands(fixture)).isEqualTo(1);
  }

  @Test
  void offlineRunningExecutionKeepsCapacityAndFreshHeartbeatRecoversSameAttempt() throws Exception {
    Fixture fixture = submitted();
    AGENT.setState(fixture.requestId(), "RUNNING", 35);
    offline();
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "UNKNOWN");
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(AGENT.queryAttempts.get()).isZero();

    online();
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "RUNNING");
    assertThat(progress(fixture)).isEqualTo(35);
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void confirmedSuccessCallbackCanReleaseAnOfflineNodesDurableReservation() throws Exception {
    Fixture fixture = submitted();
    offline();
    monitor.reconcileExecution(fixture.executionId());
    state.callback("node-a", remote(fixture, "SUCCESS", 100));
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
  }

  @Test
  void staleHeartbeatRunningCallbackKeepsUnknownUntilFreshNodeObservation() throws Exception {
    Fixture fixture = submitted();
    offline();
    state.callback("node-a", remote(fixture, "RUNNING", 60));
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "UNKNOWN");
    assertThat(executions.find(fixture.executionId())).containsEntry("status", "UNKNOWN");
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(progress(fixture)).isEqualTo(60);
    assertThat(parseCommands(fixture)).isZero();
    online();
    state.callback("node-a", remote(fixture, "RUNNING", 65));
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "RUNNING");
    assertThat(progress(fixture)).isEqualTo(65);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void remote404IsUncertaintyAndNeverReleasesCapacityOrResubmits() throws Exception {
    Fixture fixture = submitted();
    AGENT.missing = true;
    monitor.reconcileExecution(fixture.executionId());
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "UNKNOWN");
    assertThat(executions.find(fixture.executionId())).containsEntry("status", "UNKNOWN");
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(parseCommands(fixture)).isZero();
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  @Test
  void oldSubmittedCallbackAndPollCannotRegressRunningAfterOfflineRecovery() throws Exception {
    verifyRunningObservationSurvivesOffline(false);
  }

  @Test
  void runningObservationWithoutOptionalStartTimeSurvivesOfflineRecovery() throws Exception {
    verifyRunningObservationSurvivesOffline(true);
  }

  private void verifyRunningObservationSurvivesOffline(boolean omitStartTime) throws Exception {
    Fixture fixture = submitted();
    Map<String, Object> acceptedProcess = new LinkedHashMap<>(remote(fixture, "SUBMITTED", 0));
    acceptedProcess.remove("startedAt");
    state.callback("node-a", acceptedProcess);
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUBMITTED");
    assertThat(executions.find(fixture.executionId()).get("runningObservedAt")).isNull();

    Map<String, Object> running = new LinkedHashMap<>(remote(fixture, "RUNNING", 70));
    if (omitStartTime) running.remove("startedAt");
    state.callback("node-a", running);
    Map<String, Object> confirmed = executions.find(fixture.executionId());
    Object observedAt = confirmed.get("runningObservedAt");
    assertThat(observedAt).isNotNull();
    if (omitStartTime) assertThat(confirmed.get("startedAt")).isNull();
    offline();
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "UNKNOWN");
    assertThat(executions.find(fixture.executionId()))
        .containsEntry("status", "UNKNOWN")
        .containsEntry("runningObservedAt", observedAt);
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");

    online();
    Map<String, Object> staleSubmitted = new LinkedHashMap<>(remote(fixture, "SUBMITTED", 10));
    staleSubmitted.remove("startedAt");
    state.callback("node-a", staleSubmitted);
    AGENT.setState(fixture.requestId(), "SUBMITTED", 10);
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "RUNNING");
    assertThat(executions.find(fixture.executionId()))
        .containsEntry("status", "RUNNING")
        .containsEntry("runningObservedAt", observedAt)
        .containsEntry("pid", confirmed.get("pid"))
        .containsEntry("processIdentity", confirmed.get("processIdentity"))
        .containsEntry("submitRequestId", fixture.requestId());
    assertThat(progress(fixture)).isEqualTo(70);
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(parseCommands(fixture)).isZero();
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);

    state.callback("node-a", remote(fixture, "SUCCESS", 100));
    state.callback("node-a", remote(fixture, "SUCCESS", 100));
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
  }

  @Test
  void acceptanceWithoutHttpResponseIsRecoveredByOriginalRequestIdOnly() throws Exception {
    Fixture fixture = allocated();
    AGENT.accept(fixture.requestId());
    AGENT.setState(fixture.requestId(), "RUNNING", 50);
    assertThat(executions.find(fixture.executionId()).get("externalExecutionId")).isNull();
    assertThat(reservationStatus(fixture)).isEqualTo("RESERVED");
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "RUNNING");
    assertThat(executions.find(fixture.executionId()))
        .containsEntry("externalExecutionId", AGENT.external(fixture.requestId()));
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(AGENT.requestQueries.get()).isEqualTo(1);
    AGENT.setState(fixture.requestId(), "SUCCESS", 100);
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
    assertThat(AGENT.submitAttempts.get()).isZero();
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  @Test
  void cancelIntentSurvivesOfflineUnknownAndResumesAfterFreshHeartbeat() throws Exception {
    Fixture fixture = submitted();
    AGENT.cancelUnavailable = true;
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/cancel")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk());
    assertThat(cancelRequested(fixture)).isTrue();
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    offline();
    monitor.reconcileExecution(fixture.executionId());
    assertThat(cancelRequested(fixture)).isTrue();
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    online();
    AGENT.cancelUnavailable = false;
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "CANCELLED");
    assertThat(executions.find(fixture.executionId())).containsEntry("status", "CANCELLED");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isZero();
    assertThat(AGENT.cancelAttempts.get()).isGreaterThanOrEqualTo(2);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void completionWinningCancellationStillCreatesOneParseCommand() throws Exception {
    Fixture fixture = submitted();
    AGENT.setState(fixture.requestId(), "SUCCESS", 100);
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/cancel")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(executions.find(fixture.executionId())).containsEntry("status", "SUCCESS");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
    monitor.reconcileExecution(fixture.executionId());
    assertThat(parseCommands(fixture)).isEqualTo(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void invalidCancellationIdentityKeepsIntentAndCapacityUntilConfirmedResponse() throws Exception {
    Fixture fixture = submitted();
    AGENT.cancelIdentityMismatch = true;
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/cancel")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk());
    assertThat(jobs.findById(fixture.jobId()))
        .containsEntry("status", "CANCEL_REQUESTED")
        .containsEntry("failureCode", "CANCEL_FAILED");
    assertThat(cancelRequested(fixture)).isTrue();
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(parseCommands(fixture)).isZero();
    AGENT.cancelIdentityMismatch = false;
    cancellation.continueCancel(fixture.jobId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "CANCELLED");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void alreadyCompletedAcceptanceIsRecoveredWithoutAnIntermediateRunningObservation()
      throws Exception {
    Fixture fixture = allocated();
    AGENT.accept(fixture.requestId());
    AGENT.setState(fixture.requestId(), "SUCCESS", 100);
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(executions.find(fixture.executionId())).containsEntry("status", "SUCCESS");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
    assertThat(AGENT.submitAttempts.get()).isZero();
    assertThat(AGENT.requestQueries.get()).isEqualTo(1);
  }

  @Test
  void concurrentLeaseClaimHasOneOwnerAndExpiredGenerationCannotApplyTerminalObservation()
      throws Exception {
    Fixture fixture = submitted();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService claimers = Executors.newFixedThreadPool(2);
    List<ExecutionPollLeaseRepository.PollClaim> claimed = new ArrayList<>();
    try {
      List<java.util.concurrent.Callable<List<ExecutionPollLeaseRepository.PollClaim>>> calls =
          List.of(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return leases.claim(
                    "first", 1, Duration.ofSeconds(10), List.of(fixture.executionId()));
              },
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return leases.claim(
                    "second", 1, Duration.ofSeconds(10), List.of(fixture.executionId()));
              });
      var first = claimers.submit(calls.get(0));
      var second = claimers.submit(calls.get(1));
      start.countDown();
      claimed.addAll(first.get());
      claimed.addAll(second.get());
    } finally {
      claimers.shutdownNow();
    }
    assertThat(claimed).hasSize(1);
    ExecutionPollLeaseRepository.PollClaim oldClaim = claimed.get(0);
    jdbc.update(
        "UPDATE cae_execution_poll_lease SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE execution_id=?",
        fixture.executionId());
    var current =
        leases.claim("replacement", 1, Duration.ofSeconds(10), List.of(fixture.executionId()));
    assertThat(current).hasSize(1);
    assertThat(state.applyPolled(oldClaim, remote(fixture, "SUCCESS", 100)).applied()).isFalse();
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUBMITTED");
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    assertThat(parseCommands(fixture)).isZero();
    assertThat(leases.finish(oldClaim, Duration.ZERO, null)).isFalse();
    state.applyPolled(current.get(0), remote(fixture, "SUCCESS", 100));
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
  }

  @Test
  void legacySuccessInPostProcessingBackfillsOneParseCommandAndRepairsReservation()
      throws Exception {
    Fixture fixture = submitted();
    jdbc.update(
        "UPDATE cae_job_execution SET status='SUCCESS',finished_at=CURRENT_TIMESTAMP(6),exit_code=0,progress=100 WHERE id=?",
        fixture.executionId());
    jdbc.update("UPDATE cae_job SET status='POST_PROCESSING' WHERE id=?", fixture.jobId());
    assertThat(parseCommands(fixture)).isZero();
    assertThat(reservationStatus(fixture)).isEqualTo("IN_USE");
    monitor.reconcileExecution(fixture.executionId());
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isEqualTo(1);
    assertThat(AGENT.queryAttempts.get()).isZero();
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void confirmedFailedTerminalReservationIsRepairedWithoutRemoteQueryOrParse() throws Exception {
    Fixture fixture = submitted();
    jdbc.update(
        "UPDATE cae_job_execution SET status='RUNTIME_FAILED',finished_at=CURRENT_TIMESTAMP(6),exit_code=2 WHERE id=?",
        fixture.executionId());
    jdbc.update("UPDATE cae_job SET status='RUNTIME_FAILED' WHERE id=?", fixture.jobId());
    monitor.reconcileExecution(fixture.executionId());
    monitor.reconcileExecution(fixture.executionId());
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "RUNTIME_FAILED");
    assertThat(reservationStatus(fixture)).isEqualTo("RELEASED");
    assertThat(parseCommands(fixture)).isZero();
    assertThat(AGENT.queryAttempts.get()).isZero();
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  private Fixture submitted() throws Exception {
    Fixture fixture = allocated();
    assertThat(dispatch.submit(fixture.executionId())).isEqualTo(DispatchOutcome.ACCEPTED);
    return fixture;
  }

  private Fixture allocated() throws Exception {
    String name = "monitor-" + UUID.randomUUID();
    Path source = ROOT.resolve("cases").resolve(name);
    Files.createDirectories(source);
    Files.writeString(source.resolve("model.fem"), "$ synthetic input\nINCLUDE 'materials.inc'\n");
    Files.writeString(source.resolve("materials.inc"), "MAT1 SYNTHETIC\n");
    Files.writeString(
        source.resolve("manifest.json"),
        json.write(
            Map.of(
                "caseId", name,
                "entryDeck", "model.fem",
                "includes", List.of("materials.inc"),
                "analysisType", "STRUCTURE",
                "conditionCode", "BIW_TORSION",
                "modelRevision", "V37",
                "parameters", Map.of("seed", 37))));
    String response =
        mvc.perform(
                post("/api/v1/jobs")
                    .with(httpBasic("engineer", "engineer123"))
                    .contentType("application/json")
                    .content(
                        json.write(
                            Map.of(
                                "projectId",
                                1,
                                "modelRevisionId",
                                2,
                                "analysisType",
                                "STRUCTURE",
                                "conditionCode",
                                "BIW_TORSION",
                                "casePath",
                                name,
                                "resource",
                                Map.of("cpu", 4, "memoryGb", 8, "walltimeMin", 30)))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String number = json.readMap(response).get("jobNo").toString();
    long jobId = SqlRows.id(jobs.findByNo(number));
    ownedJobs.add(jobId);
    mvc.perform(
            post("/api/v1/jobs/" + number + "/validate").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/jobs/" + number + "/submit")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(Map.of("idempotencyKey", "monitor-" + UUID.randomUUID()))))
        .andExpect(status().isAccepted());
    long executionId = allocation.allocate(jobId).orElseThrow();
    return new Fixture(
        jobId, number, executionId, executions.find(executionId).get("submitRequestId").toString());
  }

  private Map<String, Object> remote(Fixture fixture, String status, int progress) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionRequestId", fixture.requestId());
    body.put("externalExecutionId", AGENT.external(fixture.requestId()));
    body.put("state", status);
    body.put("progress", progress);
    body.put("pid", 4242);
    body.put("processIdentity", "4242:stable-start");
    body.put("startedAt", Instant.now().minusSeconds(10).toString());
    if (List.of("SUCCESS", "RUNTIME_FAILED", "CANCELLED", "TIMEOUT").contains(status)) {
      body.put("finishedAt", Instant.now().toString());
      body.put("exitCode", status.equals("SUCCESS") ? 0 : 2);
    }
    return body;
  }

  private void online() {
    jdbc.update(
        "UPDATE cae_compute_node SET status='ONLINE',drained=FALSE,workspace_reachable=TRUE,last_heartbeat=CURRENT_TIMESTAMP(6),observed_cpu_used=0,observed_memory_used_gb=0 WHERE node_id='node-a'");
  }

  private void offline() {
    jdbc.update(
        "UPDATE cae_compute_node SET status='OFFLINE',last_heartbeat=TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(6)) WHERE node_id='node-a'");
  }

  private String reservationStatus(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT status FROM cae_resource_reservation WHERE execution_id=?",
        String.class,
        fixture.executionId());
  }

  private int parseCommands(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM cae_outbox_event WHERE aggregate_id=? AND event_type='RESULT_PARSE_REQUESTED' AND exchange_name='cae.command'",
        Integer.class,
        fixture.jobId());
  }

  private int jobEvents(Fixture fixture, String type) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM cae_job_event WHERE job_id=? AND event_type=?",
        Integer.class,
        fixture.jobId(),
        type);
  }

  private int progress(Fixture fixture) {
    return ((Number) executions.find(fixture.executionId()).get("progress")).intValue();
  }

  private boolean cancelRequested(Fixture fixture) {
    Object value = executions.find(fixture.executionId()).get("cancelRequested");
    return Boolean.TRUE.equals(value) || Integer.valueOf(1).equals(value);
  }

  private record Fixture(long jobId, String jobNo, long executionId, String requestId) {}

  private static Path temporaryRoot() {
    try {
      return Files.createTempDirectory("cae-monitoring-it-");
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static final class ControlledAgent {
    private final JsonCodec json = new JsonCodec(new ObjectMapper());
    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Map<String, String> accepted = new ConcurrentHashMap<>();
    private final Map<String, String> states = new ConcurrentHashMap<>();
    private final Map<String, Integer> progress = new ConcurrentHashMap<>();
    private final AtomicInteger submitAttempts = new AtomicInteger();
    private final AtomicInteger queryAttempts = new AtomicInteger();
    private final AtomicInteger requestQueries = new AtomicInteger();
    private final AtomicInteger cancelAttempts = new AtomicInteger();
    private volatile boolean missing;
    private volatile boolean cancelUnavailable;
    private volatile boolean cancelIdentityMismatch;

    ControlledAgent() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/worker/v1/jobs/submit", this::submit);
        server.createContext("/worker/v1/jobs/", this::queryOrCancel);
        server.start();
      } catch (IOException failure) {
        throw new IllegalStateException(failure);
      }
    }

    String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
      accepted.clear();
      states.clear();
      progress.clear();
      submitAttempts.set(0);
      queryAttempts.set(0);
      requestQueries.set(0);
      cancelAttempts.set(0);
      missing = false;
      cancelUnavailable = false;
      cancelIdentityMismatch = false;
    }

    void accept(String requestId) {
      accepted.computeIfAbsent(requestId, ignored -> "mon-" + UUID.randomUUID());
      states.putIfAbsent(requestId, "SUBMITTED");
      progress.putIfAbsent(requestId, 0);
    }

    String external(String requestId) {
      return accepted.get(requestId);
    }

    void setState(String requestId, String state, int percent) {
      states.put(requestId, state);
      progress.put(requestId, percent);
    }

    void close() {
      server.stop(0);
      threads.shutdownNow();
    }

    private void submit(HttpExchange exchange) throws IOException {
      submitAttempts.incrementAndGet();
      Map<String, Object> request =
          json.readMap(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String requestId = request.get("executionRequestId").toString();
      accept(requestId);
      respond(
          exchange,
          202,
          Map.of(
              "accepted", true, "externalExecutionId", external(requestId), "state", "SUBMITTED"));
    }

    private void queryOrCancel(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      boolean cancel = path.endsWith("/cancel");
      if (cancel) cancelAttempts.incrementAndGet();
      else queryAttempts.incrementAndGet();
      if (missing) {
        respond(exchange, 404, Map.of());
        return;
      }
      String requestId;
      if (path.startsWith("/worker/v1/jobs/by-request/")) {
        requestQueries.incrementAndGet();
        requestId = path.substring("/worker/v1/jobs/by-request/".length());
      } else {
        String externalId = path.substring("/worker/v1/jobs/".length()).replace("/cancel", "");
        requestId =
            accepted.entrySet().stream()
                .filter(entry -> entry.getValue().equals(externalId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
      }
      if (requestId == null || !accepted.containsKey(requestId)) {
        respond(exchange, 404, Map.of());
        return;
      }
      if (cancel && cancelUnavailable) {
        respond(exchange, 503, Map.of("detail", "controlled cancellation outage"));
        return;
      }
      if (cancel
          && !List.of("SUCCESS", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED")
              .contains(states.get(requestId))) states.put(requestId, "CANCELLED");
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("externalExecutionId", external(requestId));
      body.put(
          "executionRequestId", cancel && cancelIdentityMismatch ? "wrong-request-id" : requestId);
      body.put("state", states.get(requestId));
      body.put("progress", progress.get(requestId));
      body.put("pid", 4242);
      body.put("processIdentity", "4242:stable-start");
      body.put("startedAt", Instant.now().minusSeconds(10).toString());
      if (List.of("SUCCESS", "RUNTIME_FAILED", "CANCELLED", "TIMEOUT")
          .contains(states.get(requestId))) {
        body.put("finishedAt", Instant.now().toString());
        body.put("exitCode", states.get(requestId).equals("SUCCESS") ? 0 : 2);
      }
      respond(exchange, 200, body);
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> body)
        throws IOException {
      byte[] bytes = json.write(body).getBytes(StandardCharsets.UTF_8);
      try {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally {
        exchange.close();
      }
    }
  }
}
