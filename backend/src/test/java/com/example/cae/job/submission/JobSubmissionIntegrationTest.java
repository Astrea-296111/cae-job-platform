package com.example.cae.job.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.resource.ResourceReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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

/** Real MySQL transactions and real HTTP transport against a controlled, idempotent Agent. */
@SpringBootTest
@AutoConfigureMockMvc
class JobSubmissionIntegrationTest {
  private static final Path ROOT = temporaryRoot();
  private static final FakeAgent AGENT = new FakeAgent();

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("CASE_ROOT", () -> ROOT.resolve("cases").toString());
    registry.add("DATA_ROOT", () -> ROOT.resolve("data").toString());
    registry.add("WORKER_URLS", () -> "node-a=" + AGENT.origin());
    // Hold/release latches control races; leave enough transport budget to persist cancel intent.
    registry.add("cae.agent.request-timeout-ms", () -> 2000);
    registry.add("cae.background.enabled", () -> false);
    registry.add("cae.mq.listeners-enabled", () -> false);
  }

  @Autowired MockMvc mvc;
  @Autowired JsonCodec json;
  @Autowired JdbcTemplate jdbc;
  @Autowired JobRepository jobs;
  @Autowired ExecutionRepository executions;
  @Autowired JobSubmissionService submission;
  @Autowired JobAllocationService allocation;
  @Autowired ExecutionDispatchService dispatch;
  @Autowired JobCommandService commands;
  @Autowired JobCancellationService cancellation;
  @Autowired ResourceReservationService resources;
  private final List<Long> ownedJobs = new ArrayList<>();
  private List<Map<String, Object>> originalNodes;

  @BeforeEach
  void controlledCapacity() {
    AGENT.reset();
    originalNodes = jdbc.query("SELECT * FROM cae_compute_node", SqlRows.MAPPER);
    jdbc.update("UPDATE cae_compute_node SET drained=TRUE");
    jdbc.update(
        "UPDATE cae_compute_node SET status='ONLINE',drained=FALSE,workspace_reachable=TRUE,"
            + "last_heartbeat=CURRENT_TIMESTAMP(6),observed_cpu_used=0,observed_memory_used_gb=0"
            + " WHERE node_id='node-a'");
  }

  @AfterEach
  void releaseOwnedReservationsAndRestoreNodes() {
    AGENT.releaseResponse.countDown();
    for (long jobId : ownedJobs) {
      for (Map<String, Object> execution : executions.history(jobId)) {
        resources.releaseByExecution(SqlRows.id(execution));
      }
    }
    for (Map<String, Object> node : originalNodes) {
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
  }

  @AfterAll
  static void stopAgent() {
    AGENT.close();
  }

  @Test
  void concurrentDuplicateCommandsAndDispatchesCreateOneDurableExecution() throws Exception {
    Map<String, Object> job = readyJob();
    String number = job.get("jobNo").toString();
    ExecutorService callers = Executors.newFixedThreadPool(12);
    try {
      List<Callable<Integer>> requests = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        requests.add(
            () ->
                mvc.perform(
                        post("/api/v1/jobs/" + number + "/submit")
                            .with(httpBasic("engineer", "engineer123"))
                            .contentType("application/json")
                            .content("{\"idempotencyKey\":\"same-submit\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus());
      }
      for (var response : callers.invokeAll(requests)) assertThat(response.get()).isEqualTo(202);
      List<Callable<Void>> dispatches = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        dispatches.add(
            () -> {
              submission.execute(SqlRows.id(job));
              return null;
            });
      }
      for (var dispatch : callers.invokeAll(dispatches)) dispatch.get();
    } finally {
      callers.shutdownNow();
    }

    assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    assertThat(AGENT.accepted.size()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_job_submission_request WHERE job_id=?",
                Integer.class,
                SqlRows.id(job)))
        .isEqualTo(1);
    Map<String, Object> execution = currentExecution(job);
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "SUBMITTED")
        .containsEntry("currentExecutionId", SqlRows.id(execution));
    assertThat(execution.get("submitRequestId"))
        .isEqualTo(AGENT.accepted.keySet().iterator().next());
    assertThat(reservationStatus(execution)).isEqualTo("IN_USE");
    int attempts = AGENT.submitAttempts.get();
    assertThat(dispatch.submit(SqlRows.id(execution))).isEqualTo(DispatchOutcome.NOOP);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(attempts);
  }

  @Test
  void sameIdempotencyKeyCannotChangeTheRequestedExecutionOptions() throws Exception {
    Map<String, Object> job = readyJob();
    command(
        job,
        "submit",
        "engineer",
        Map.of("idempotencyKey", "fixed", "mock", Map.of("runtimeSeconds", 1)),
        202);
    command(
        job,
        "submit",
        "engineer",
        Map.of("idempotencyKey", "fixed", "mock", Map.of("runtimeSeconds", 2)),
        409);
    assertThat(executions.history(SqlRows.id(job))).isEmpty();
    assertThat(AGENT.submitAttempts.get()).isZero();
  }

  @Test
  void acceptedThenTimedOutHttpRetryReusesTheStoredAttemptAndReservation() throws Exception {
    AGENT.mode = Mode.TIMEOUT_FIRST;
    Map<String, Object> job = readyJob();
    command(job, "submit", "engineer", Map.of("idempotencyKey", "timeout-submit"), 202);

    long executionId = allocation.allocate(SqlRows.id(job)).orElseThrow();
    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.RETRYABLE_UNCERTAIN);
    Map<String, Object> first = currentExecution(job);
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "SUBMITTING")
        .containsEntry("failureCode", "AGENT_TIMEOUT");
    assertThat(first.get("externalExecutionId")).isNull();
    assertThat(reservationStatus(first)).isEqualTo("RESERVED");
    assertThat(AGENT.accepted).containsKey(first.get("submitRequestId").toString());
    AGENT.releaseResponse.countDown();

    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.ACCEPTED);
    Map<String, Object> retried = currentExecution(job);
    assertThat(SqlRows.id(retried)).isEqualTo(SqlRows.id(first));
    assertThat(retried.get("submitRequestId")).isEqualTo(first.get("submitRequestId"));
    assertThat(retried.get("request")).isEqualTo(first.get("request"));
    assertThat(retried.get("externalExecutionId"))
        .isEqualTo(AGENT.accepted.get(first.get("submitRequestId").toString()));
    assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(2);
    assertThat(AGENT.accepted).hasSize(1);
    assertThat(reservationStatus(retried)).isEqualTo("IN_USE");
  }

  @Test
  void ambiguousServerFailureKeepsCapacityAndCannotCreateANewAttempt() throws Exception {
    AGENT.mode = Mode.ERROR_AFTER_ACCEPT;
    Map<String, Object> job = readyJob();
    int cpuBefore =
        jdbc.queryForObject(
            "SELECT cpu_reserved FROM cae_compute_node WHERE node_id='node-a'", Integer.class);
    int memoryBefore =
        jdbc.queryForObject(
            "SELECT memory_reserved_gb FROM cae_compute_node WHERE node_id='node-a'",
            Integer.class);
    int licensesBefore =
        jdbc.queryForObject(
            "SELECT reserved_tokens FROM cae_license_pool WHERE feature='OPTISTRUCT'",
            Integer.class);
    command(job, "submit", "engineer", Map.of("idempotencyKey", "uncertain"), 202);
    long executionId = allocation.allocate(SqlRows.id(job)).orElseThrow();
    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.RETRYABLE_UNCERTAIN);
    Map<String, Object> first = currentExecution(job);
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "SUBMITTING")
        .containsEntry("failureCode", "UNKNOWN_REMOTE_STATE");
    assertThat(reservationStatus(first)).isEqualTo("RESERVED");
    command(job, "retry", "engineer", Map.of("idempotencyKey", "repeat-uncertain"), 202);
    assertThat(allocation.allocate(SqlRows.id(job))).contains(executionId);
    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.RETRYABLE_UNCERTAIN);
    assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    assertThat(currentExecution(job).get("submitRequestId"))
        .isEqualTo(first.get("submitRequestId"));
    assertThat(reservationStatus(first)).isEqualTo("RESERVED");
    assertThat(AGENT.accepted).hasSize(1);

    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.RETRYABLE_UNCERTAIN);
    String logicalEventId = "submit-" + UUID.randomUUID();
    dispatch.deliveryExhausted(
        executionId, logicalEventId, 3, "Three uncertain submit deliveries exhausted");
    Map<String, Object> exhausted = currentExecution(job);
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "UNKNOWN")
        .containsEntry("currentExecutionId", executionId)
        .containsEntry("failureCode", "UNKNOWN_REMOTE_STATE");
    assertThat(exhausted)
        .containsEntry("status", "UNKNOWN")
        .containsEntry("submitRequestId", first.get("submitRequestId"))
        .containsEntry("request", first.get("request"));
    assertThat(SqlRows.id(exhausted)).isEqualTo(executionId);
    assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    assertThat(reservationStatus(exhausted)).isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT cpu_reserved FROM cae_compute_node WHERE node_id='node-a'", Integer.class))
        .isEqualTo(cpuBefore + 4);
    assertThat(
            jdbc.queryForObject(
                "SELECT memory_reserved_gb FROM cae_compute_node WHERE node_id='node-a'",
                Integer.class))
        .isEqualTo(memoryBefore + 8);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_tokens FROM cae_license_pool WHERE feature='OPTISTRUCT'",
                Integer.class))
        .isEqualTo(licensesBefore + 1);
    assertThat(jobs.events(SqlRows.id(job)).stream().map(event -> event.get("eventType")))
        .filteredOn("SUBMIT_DELIVERY_EXHAUSTED"::equals)
        .hasSize(1);
    Object version = jobs.findById(SqlRows.id(job)).get("version");
    int events = jobs.events(SqlRows.id(job)).size();
    dispatch.deliveryExhausted(executionId, logicalEventId, 3, "Repeated exhaustion notification");
    assertThat(jobs.findById(SqlRows.id(job)).get("version")).isEqualTo(version);
    assertThat(currentExecution(job).get("version")).isEqualTo(exhausted.get("version"));
    assertThat(jobs.events(SqlRows.id(job))).hasSize(events);
    assertThat(allocation.allocate(SqlRows.id(job))).isEmpty();
    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.NOOP);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(3);
  }

  @Test
  void explicitRemoteRejectionReleasesReservationAndPreservesFailure() throws Exception {
    AGENT.mode = Mode.REJECT;
    Map<String, Object> job = readyJob();
    command(job, "submit", "engineer", Map.of("idempotencyKey", "rejected"), 202);
    long executionId = allocation.allocate(SqlRows.id(job)).orElseThrow();
    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.PERMANENT_FAILURE);
    Map<String, Object> execution = currentExecution(job);
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "SUBMIT_FAILED")
        .containsEntry("failureCode", "SUBMIT_REJECTED");
    assertThat(execution.get("status")).isEqualTo("SUBMIT_FAILED");
    assertThat(reservationStatus(execution)).isEqualTo("RELEASED");
    assertThat(AGENT.accepted).isEmpty();
    assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.NOOP);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
  }

  @Test
  void repeatedQueuedCancellationNeverCreatesARemoteExecution() throws Exception {
    Map<String, Object> job = readyJob();
    command(job, "submit", "engineer", Map.of("idempotencyKey", "cancel-queued"), 202);
    command(job, "cancel", "engineer", Map.of(), 200);
    command(job, "cancel", "engineer", Map.of(), 200);
    submission.execute(SqlRows.id(job));
    assertThat(jobs.findById(SqlRows.id(job)).get("status")).isEqualTo("CANCELLED");
    assertThat(executions.history(SqlRows.id(job))).isEmpty();
    assertThat(AGENT.submitAttempts.get()).isZero();
    assertThat(jobs.events(SqlRows.id(job)).stream().map(event -> event.get("eventType")))
        .filteredOn("JOB_CANCELLED"::equals)
        .hasSize(1);
  }

  @Test
  void remoteCancellationFailurePersistsIntentAndKeepsCapacityUntilConfirmation() throws Exception {
    Map<String, Object> job = readyJob();
    command(job, "submit", "engineer", Map.of("idempotencyKey", "cancel-remote"), 202);
    submission.execute(SqlRows.id(job));
    Map<String, Object> execution = currentExecution(job);
    AGENT.cancelUnavailable = true;
    command(job, "cancel", "engineer", Map.of(), 200);
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "CANCEL_REQUESTED")
        .containsEntry("failureCode", "CANCEL_FAILED");
    assertThat(currentExecution(job).get("cancelRequested")).isIn(true, 1);
    assertThat(reservationStatus(execution)).isEqualTo("IN_USE");

    AGENT.cancelUnavailable = false;
    cancellation.continueCancel(SqlRows.id(job));
    assertThat(jobs.findById(SqlRows.id(job)).get("status")).isEqualTo("CANCELLED");
    assertThat(currentExecution(job).get("status")).isEqualTo("CANCELLED");
    assertThat(reservationStatus(execution)).isEqualTo("RELEASED");
    cancellation.continueCancel(SqlRows.id(job));
    assertThat(reservationStatus(execution)).isEqualTo("RELEASED");
    assertThat(AGENT.accepted).hasSize(1);
  }

  @Test
  void cancellationDuringAcceptedButUnansweredSubmitUsesTheExistingRemoteRequest()
      throws Exception {
    AGENT.mode = Mode.TIMEOUT_FIRST;
    Map<String, Object> job = readyJob();
    command(job, "submit", "engineer", Map.of("idempotencyKey", "submit-cancel-race"), 202);
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      var inFlight = caller.submit(() -> submission.execute(SqlRows.id(job)));
      assertThat(AGENT.firstAccepted.await(2, TimeUnit.SECONDS)).isTrue();
      command(job, "cancel", "engineer", Map.of(), 200);
      AGENT.releaseResponse.countDown();
      inFlight.get(5, TimeUnit.SECONDS);
      Map<String, Object> execution = currentExecution(job);
      assertThat(jobs.findById(SqlRows.id(job)).get("status")).isEqualTo("CANCELLED");
      assertThat(execution.get("status")).isEqualTo("CANCELLED");
      assertThat(reservationStatus(execution)).isEqualTo("RELEASED");
      assertThat(AGENT.accepted).hasSize(1);
      assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    } finally {
      AGENT.releaseResponse.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  void confirmedAcceptanceRemainsAcceptedWhenTheRacingCancellationRpcReturns503() throws Exception {
    AGENT.mode = Mode.TIMEOUT_FIRST;
    AGENT.cancelUnavailable = true;
    Map<String, Object> job = readyJob();
    command(
        job, "submit", "engineer", Map.of("idempotencyKey", "accepted-cancel-unavailable"), 202);
    long executionId = allocation.allocate(SqlRows.id(job)).orElseThrow();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      var outcome = caller.submit(() -> dispatch.submit(executionId));
      assertThat(AGENT.firstAccepted.await(2, TimeUnit.SECONDS)).isTrue();
      command(job, "cancel", "engineer", Map.of(), 200);
      AGENT.releaseResponse.countDown();
      assertThat(outcome.get(5, TimeUnit.SECONDS))
          .isIn(DispatchOutcome.ACCEPTED, DispatchOutcome.NOOP);
      Map<String, Object> execution = currentExecution(job);
      assertThat(execution).containsEntry("status", "SUBMITTED");
      assertThat(execution.get("externalExecutionId"))
          .isEqualTo(AGENT.accepted.get(execution.get("submitRequestId")));
      assertThat(execution.get("cancelRequested")).isIn(true, 1);
      assertThat(jobs.findById(SqlRows.id(job)))
          .containsEntry("status", "CANCEL_REQUESTED")
          .containsEntry("failureCode", "CANCEL_FAILED");
      assertThat(reservationStatus(execution)).isEqualTo("IN_USE");
      assertThat(AGENT.cancelAttempts.get()).isEqualTo(2);
      assertThat(dispatch.submit(executionId)).isEqualTo(DispatchOutcome.NOOP);
      assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
      assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    } finally {
      AGENT.releaseResponse.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  void sameProjectMembershipDoesNotGrantAnotherUsersCommandRights() throws Exception {
    Map<String, Object> job = readyJob();
    command(job, "submit", "lead", Map.of("idempotencyKey", "not-owner"), 403);
    command(job, "cancel", "lead", Map.of(), 403);
    assertThat(jobs.findById(SqlRows.id(job)).get("status")).isEqualTo("READY");
    command(job, "cancel", "admin", Map.of(), 200);
    command(job, "retry", "lead", Map.of("idempotencyKey", "not-owner-retry"), 403);
    command(job, "retry", "admin", Map.of("idempotencyKey", "admin-retry"), 202);
    assertThat(
            jdbc.queryForList(
                "SELECT actor FROM cae_audit_log WHERE target_id=? AND operation IN ('JOB_CANCEL','JOB_RETRY')",
                String.class,
                job.get("jobNo")))
        .hasSize(2)
        .containsOnly("admin");
  }

  @Test
  void retryingCancelledUnvalidatedInputMustRunValidationBeforeScheduling() throws Exception {
    Map<String, Object> job = createJob(true);
    command(job, "cancel", "engineer", Map.of(), 200);
    assertThat(jobs.findById(SqlRows.id(job)).get("workspace")).isNull();
    command(job, "retry", "engineer", Map.of("idempotencyKey", "validate-cancelled"), 202);
    commands.finishValidation(SqlRows.id(job));
    submission.execute(SqlRows.id(job));
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "VALIDATION_FAILED")
        .containsEntry("failureCode", "INPUT_INCLUDE_MISSING");
    assertThat(executions.history(SqlRows.id(job))).isEmpty();
    assertThat(AGENT.submitAttempts.get()).isZero();
    assertThat(jobs.events(SqlRows.id(job)).stream().map(event -> event.get("eventType")))
        .contains("VALIDATION_STARTED", "VALIDATION_FAILED");
  }

  @Test
  void snapshotTamperingAfterQueueingCannotConsumeResourcesOrStartASolver() throws Exception {
    Map<String, Object> job = readyJob();
    command(job, "submit", "engineer", Map.of("idempotencyKey", "tampered"), 202);
    Path material = Path.of(job.get("workspace").toString()).resolve("input/materials.inc");
    Files.setPosixFilePermissions(
        material, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.writeString(material, "tampered after validation\n");
    submission.execute(SqlRows.id(job));
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "SUBMIT_FAILED")
        .containsEntry("failureCode", "INPUT_INVALID");
    assertThat(executions.history(SqlRows.id(job))).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_resource_reservation WHERE job_id=?",
                Integer.class,
                SqlRows.id(job)))
        .isZero();
    assertThat(AGENT.submitAttempts.get()).isZero();
  }

  private Map<String, Object> readyJob() throws Exception {
    Map<String, Object> job = createJob(false);
    mvc.perform(
            post("/api/v1/jobs/" + job.get("jobNo") + "/validate")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"));
    return jobs.findById(SqlRows.id(job));
  }

  private Map<String, Object> createJob(boolean missingInclude) throws Exception {
    String name = "case-" + UUID.randomUUID();
    Path source = ROOT.resolve("cases").resolve(name);
    Files.createDirectories(source);
    Files.writeString(source.resolve("model.fem"), "$ synthetic input\nINCLUDE 'materials.inc'\n");
    if (!missingInclude) Files.writeString(source.resolve("materials.inc"), "MAT1 SYNTHETIC\n");
    Files.writeString(
        source.resolve("manifest.json"),
        json.write(
            Map.of(
                "caseId",
                name,
                "entryDeck",
                "model.fem",
                "includes",
                List.of("materials.inc"),
                "analysisType",
                "STRUCTURE",
                "conditionCode",
                "BIW_TORSION",
                "modelRevision",
                "V37",
                "parameters",
                Map.of("seed", 37))));
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
    Map<String, Object> job = jobs.findByNo(json.readMap(response).get("jobNo").toString());
    ownedJobs.add(SqlRows.id(job));
    return job;
  }

  private void command(
      Map<String, Object> job,
      String operation,
      String user,
      Map<String, Object> body,
      int expectedStatus)
      throws Exception {
    mvc.perform(
            post("/api/v1/jobs/" + job.get("jobNo") + "/" + operation)
                .with(httpBasic(user, user + "123"))
                .contentType("application/json")
                .content(json.write(body)))
        .andExpect(status().is(expectedStatus));
  }

  private Map<String, Object> currentExecution(Map<String, Object> job) {
    return executions.find(
        ((Number) jobs.findById(SqlRows.id(job)).get("currentExecutionId")).longValue());
  }

  private String reservationStatus(Map<String, Object> execution) {
    return jdbc.queryForObject(
        "SELECT status FROM cae_resource_reservation WHERE execution_id=?",
        String.class,
        SqlRows.id(execution));
  }

  private static Path temporaryRoot() {
    try {
      return Files.createTempDirectory("cae-submission-it-");
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private enum Mode {
    ACCEPT,
    TIMEOUT_FIRST,
    ERROR_AFTER_ACCEPT,
    REJECT
  }

  private static final class FakeAgent {
    private final JsonCodec json = new JsonCodec(new ObjectMapper());
    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Map<String, String> accepted = new ConcurrentHashMap<>();
    private final AtomicInteger submitAttempts = new AtomicInteger();
    private final AtomicInteger cancelAttempts = new AtomicInteger();
    private volatile Mode mode = Mode.ACCEPT;
    private volatile CountDownLatch releaseResponse = new CountDownLatch(1);
    private volatile CountDownLatch firstAccepted = new CountDownLatch(1);
    private volatile boolean cancelUnavailable;

    FakeAgent() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/worker/v1/jobs/submit", this::submit);
        server.createContext("/worker/v1/jobs/", this::queryOrCancel);
        server.start();
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
    }

    String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
      accepted.clear();
      submitAttempts.set(0);
      cancelAttempts.set(0);
      mode = Mode.ACCEPT;
      releaseResponse = new CountDownLatch(1);
      firstAccepted = new CountDownLatch(1);
      cancelUnavailable = false;
    }

    void close() {
      releaseResponse.countDown();
      server.stop(0);
      threads.shutdownNow();
    }

    private void submit(HttpExchange exchange) throws IOException {
      int attempt = submitAttempts.incrementAndGet();
      if (mode == Mode.REJECT) {
        respond(exchange, 422, Map.of("detail", "controlled rejection"));
        return;
      }
      Map<String, Object> request =
          json.readMap(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String requestId = request.get("executionRequestId").toString();
      String external = accepted.computeIfAbsent(requestId, ignored -> "fake-" + UUID.randomUUID());
      firstAccepted.countDown();
      if (mode == Mode.ERROR_AFTER_ACCEPT) {
        respond(exchange, 503, Map.of("detail", "response unavailable after acceptance"));
        return;
      }
      if (mode == Mode.TIMEOUT_FIRST && attempt == 1) {
        try {
          releaseResponse.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
      respond(
          exchange,
          202,
          Map.of("accepted", true, "externalExecutionId", external, "state", "SUBMITTED"));
    }

    private void queryOrCancel(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/cancel")) cancelAttempts.incrementAndGet();
      if (path.startsWith("/worker/v1/jobs/by-request/")) {
        String requestId = path.substring("/worker/v1/jobs/by-request/".length());
        String external = accepted.get(requestId);
        if (external == null) {
          respond(exchange, 404, Map.of());
          return;
        }
        respond(
            exchange,
            200,
            Map.of(
                "externalExecutionId",
                external,
                "executionRequestId",
                requestId,
                "state",
                "RUNNING"));
        return;
      }
      if (path.endsWith("/cancel") && cancelUnavailable) {
        respond(exchange, 503, Map.of("detail", "controlled cancellation dependency failure"));
        return;
      }
      String external = path.substring("/worker/v1/jobs/".length()).replace("/cancel", "");
      String requestId =
          accepted.entrySet().stream()
              .filter(entry -> entry.getValue().equals(external))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow();
      respond(
          exchange,
          200,
          Map.of(
              "externalExecutionId",
              external,
              "executionRequestId",
              requestId,
              "state",
              path.endsWith("/cancel") ? "CANCELLED" : "RUNNING"));
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> body)
        throws IOException {
      byte[] encoded = json.write(body).getBytes(StandardCharsets.UTF_8);
      try {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
      } finally {
        exchange.close();
      }
    }
  }
}
