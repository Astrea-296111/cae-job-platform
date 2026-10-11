package com.example.cae.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.outbox.OutboxPublisher;
import com.example.cae.outbox.OutboxRepository;
import com.example.cae.resource.ResourceReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real MySQL and real RabbitMQ with a controlled asynchronous Agent; no solver wait in consumers.
 */
@SpringBootTest(
    properties = {
      "cae.background.enabled=false", "cae.mq.listeners-enabled=true",
      "cae.mq.max-delivery-attempts=3", "cae.mq.retry-base-ms=1000",
      "cae.mq.submit-concurrency=2"
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SubmitMessagingIntegrationTest {
  private static final Path ROOT = temporaryRoot();
  private static final FakeAgent AGENT = new FakeAgent();

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("CASE_ROOT", () -> ROOT.resolve("cases").toString());
    registry.add("DATA_ROOT", () -> ROOT.resolve("data").toString());
    registry.add("WORKER_URLS", () -> "node-a=" + AGENT.origin());
    registry.add("cae.agent.request-timeout-ms", () -> 5000);
  }

  @Autowired MockMvc mvc;
  @Autowired JsonCodec json;
  @Autowired JdbcTemplate jdbc;
  @Autowired JobRepository jobs;
  @Autowired ExecutionRepository executions;
  @Autowired ResourceReservationService resources;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxPublisher publisher;
  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin admin;
  @Autowired CachingConnectionFactory connection;
  @Autowired TransactionTemplate tx;
  @Autowired MeterRegistry metrics;
  private final List<Long> ownedJobs = new ArrayList<>();
  private List<Map<String, Object>> originalNodes;

  @BeforeEach
  void prepareOwnedVhostAndCapacity() {
    assertThat(connection.getVirtualHost()).startsWith("cae_test_");
    admin.purgeQueue(RabbitTopology.SUBMIT_QUEUE, false);
    admin.purgeQueue(RabbitTopology.SUBMIT_QUEUE + ".dlq", false);
    AGENT.reset();
    originalNodes = jdbc.query("SELECT * FROM cae_compute_node", SqlRows.MAPPER);
    jdbc.update("UPDATE cae_compute_node SET drained=TRUE");
    jdbc.update(
        "UPDATE cae_compute_node SET status='ONLINE',drained=FALSE,workspace_reachable=TRUE,last_heartbeat=CURRENT_TIMESTAMP(6),observed_cpu_used=0,observed_memory_used_gb=0 WHERE node_id='node-a'");
  }

  @AfterEach
  void restoreCapacityAndReleaseOwnedReservations() {
    AGENT.releaseResponse.countDown();
    for (long id : ownedJobs) {
      for (Map<String, Object> execution : executions.history(id))
        resources.releaseByExecution(SqlRows.id(execution));
    }
    for (Map<String, Object> node : originalNodes) {
      jdbc.update(
          "UPDATE cae_compute_node SET status=?,drained=?,workspace_reachable=?,last_heartbeat=?,observed_cpu_used=?,observed_memory_used_gb=? WHERE node_id=?",
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
  void duplicateAllocationAndExecutionDeliveriesCreateOnlyOneAttemptAndRemoteProcess()
      throws Exception {
    Map<String, Object> job = queuedJob();
    CommandEnvelope allocation = envelope(commandId(job, "JOB_SUBMIT_REQUESTED"));
    long duplicateAllocation = append(allocation.retry(0));
    assertThat(
            publisher.publishIds(
                List.of(commandId(job, "JOB_SUBMIT_REQUESTED"), duplicateAllocation)))
        .isEqualTo(2);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(executions.history(SqlRows.id(job))).hasSize(1);
              assertThat(inboxStatus(allocation)).isEqualTo("DONE");
            });

    long dispatchId = commandId(job, "EXECUTION_SUBMIT_REQUESTED");
    CommandEnvelope dispatch = envelope(dispatchId);
    AGENT.holdResponse = true;
    assertThat(publisher.publishIds(List.of(dispatchId))).isEqualTo(1);
    assertThat(AGENT.firstAccepted.await(5, TimeUnit.SECONDS)).isTrue();
    long duplicateDispatch = append(dispatch.retry(0));
    assertThat(publisher.publishIds(List.of(duplicateDispatch))).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM cae_outbox_event WHERE logical_event_id=?",
                            Integer.class,
                            dispatch.eventId()))
                    .isEqualTo(3));
    AGENT.releaseResponse.countDown();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(inboxStatus(dispatch)).isEqualTo("DONE"));
    // The concurrent duplicate was durably deferred. Once the original is DONE it is a no-op.
    long deferred =
        jdbc.queryForObject(
            "SELECT MAX(id) FROM cae_outbox_event WHERE logical_event_id=?",
            Long.class,
            dispatch.eventId());
    jdbc.update(
        "UPDATE cae_outbox_event SET next_retry_time=UTC_TIMESTAMP(6) WHERE id=?", deferred);
    double ackedBefore = metrics.counter("cae.mq.commands.acked").count();
    assertThat(publisher.publishIds(List.of(deferred))).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(metrics.counter("cae.mq.commands.acked").count())
                    .isGreaterThan(ackedBefore));
    assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    assertThat(AGENT.accepted).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(1);
    assertThat(jobs.findById(SqlRows.id(job))).containsEntry("status", "SUBMITTED");
  }

  @Test
  void consumerAcknowledgesAgentAcceptanceWhileTheRemoteSolverStillRuns() throws Exception {
    Map<String, Object> job = queuedJob();
    long allocationId = commandId(job, "JOB_SUBMIT_REQUESTED");
    publishAndAwaitInboxDone(allocationId);
    long dispatchId = commandId(job, "EXECUTION_SUBMIT_REQUESTED");
    CommandEnvelope dispatch = envelope(dispatchId);
    double before = metrics.counter("cae.mq.commands.acked").count();
    assertThat(publisher.publishIds(List.of(dispatchId))).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(inboxStatus(dispatch)).isEqualTo("DONE");
              assertThat(metrics.counter("cae.mq.commands.acked").count()).isGreaterThan(before);
            });
    assertThat(AGENT.solverFinished.getCount()).isEqualTo(1);
    assertThat(AGENT.accepted).hasSize(1);
    assertThat(jobs.findById(SqlRows.id(job))).containsEntry("status", "SUBMITTED");
    assertThat(reservationStatus(job)).isEqualTo("IN_USE");
    assertThat(rabbit.receive(RabbitTopology.SUBMIT_QUEUE)).isNull();
  }

  @Test
  void uncertainAcceptanceRetriesDurablyThenDeadLettersWithoutAnotherAttemptOrCapacityRelease()
      throws Exception {
    AGENT.alwaysUnavailable = true;
    Map<String, Object> job = queuedJob();
    publishAndAwaitInboxDone(commandId(job, "JOB_SUBMIT_REQUESTED"));
    long dispatchId = commandId(job, "EXECUTION_SUBMIT_REQUESTED");
    CommandEnvelope dispatch = envelope(dispatchId);
    assertThat(publisher.publishIds(List.of(dispatchId))).isEqualTo(1);
    for (int failures = 1; failures <= 3; failures++) {
      int expected = failures;
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                assertThat(inboxStatus(dispatch)).isEqualTo(expected == 3 ? "DEAD" : "RETRY");
                assertThat(
                        jdbc.queryForObject(
                            "SELECT retry_count FROM cae_message_inbox WHERE logical_event_id=? AND consumer_name=?",
                            Integer.class,
                            dispatch.eventId(),
                            SubmitCommandListener.CONSUMER))
                    .isEqualTo(expected);
              });
      if (failures < 3) {
        long retryId =
            jdbc.queryForObject(
                "SELECT id FROM cae_outbox_event WHERE logical_event_id=? AND delivery_attempt=?",
                Long.class,
                dispatch.eventId(),
                failures);
        assertThat(publisher.publishIds(List.of(retryId))).isZero();
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> publisher.publishIds(List.of(retryId)) == 1);
      }
    }
    Message dead = rabbit.receive(RabbitTopology.SUBMIT_QUEUE + ".dlq", 5000);
    assertThat(dead).isNotNull();
    CommandEnvelope deadEnvelope =
        CommandEnvelope.from(json.readMap(new String(dead.getBody(), StandardCharsets.UTF_8)));
    assertThat(deadEnvelope.eventId()).isEqualTo(dispatch.eventId());
    assertThat(deadEnvelope.deliveryAttempt()).isEqualTo(2);
    assertThat(jobs.findById(SqlRows.id(job)))
        .containsEntry("status", "UNKNOWN")
        .containsEntry("failureCode", "UNKNOWN_REMOTE_STATE");
    assertThat(reservationStatus(job)).isEqualTo("RESERVED");
    assertThat(executions.history(SqlRows.id(job))).hasSize(1);
    assertThat(AGENT.submitAttempts.get()).isEqualTo(3);
    assertThat(AGENT.accepted).hasSize(1);
  }

  private void publishAndAwaitInboxDone(long id) {
    CommandEnvelope command = envelope(id);
    assertThat(publisher.publishIds(List.of(id))).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(inboxStatus(command)).isEqualTo("DONE"));
  }

  private long append(CommandEnvelope envelope) {
    tx.executeWithoutResult(
        transaction ->
            outbox.append(
                envelope,
                RabbitTopology.COMMAND_EXCHANGE,
                RabbitTopology.route(envelope.eventType()),
                Instant.now()));
    return jdbc.queryForObject(
        "SELECT id FROM cae_outbox_event WHERE event_id=?", Long.class, envelope.deliveryId());
  }

  private CommandEnvelope envelope(long id) {
    return CommandEnvelope.from(
        json.readMap(
            jdbc.queryForObject(
                "SELECT payload FROM cae_outbox_event WHERE id=?", String.class, id)));
  }

  private String inboxStatus(CommandEnvelope envelope) {
    List<String> statuses =
        jdbc.queryForList(
            "SELECT status FROM cae_message_inbox WHERE logical_event_id=? AND consumer_name=?",
            String.class,
            envelope.eventId(),
            SubmitCommandListener.CONSUMER);
    return statuses.isEmpty() ? "ABSENT" : statuses.get(0);
  }

  private long commandId(Map<String, Object> job, String type) {
    return jdbc.queryForObject(
        "SELECT id FROM cae_outbox_event WHERE aggregate_id=? AND event_type=? AND exchange_name=? ORDER BY id LIMIT 1",
        Long.class,
        SqlRows.id(job),
        type,
        RabbitTopology.COMMAND_EXCHANGE);
  }

  private String reservationStatus(Map<String, Object> job) {
    return jdbc.queryForObject(
        "SELECT status FROM cae_resource_reservation WHERE job_id=? AND status<>'RELEASED'",
        String.class,
        SqlRows.id(job));
  }

  private Map<String, Object> queuedJob() throws Exception {
    String name = "mq-case-" + UUID.randomUUID();
    Path source = ROOT.resolve("cases").resolve(name);
    Files.createDirectories(source);
    Files.writeString(
        source.resolve("model.fem"), "$ public synthetic input\nINCLUDE 'materials.inc'\n");
    Files.writeString(source.resolve("materials.inc"), "MAT1 SYNTHETIC\n");
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
    String created =
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
    String number = json.readMap(created).get("jobNo").toString();
    Map<String, Object> job = jobs.findByNo(number);
    ownedJobs.add(SqlRows.id(job));
    mvc.perform(
            post("/api/v1/jobs/" + number + "/validate").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/jobs/" + number + "/submit")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(Map.of("idempotencyKey", "mq-" + UUID.randomUUID()))))
        .andExpect(status().isAccepted());
    return jobs.findByNo(number);
  }

  private static Path temporaryRoot() {
    try {
      return Files.createTempDirectory("cae-messaging-it-");
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static final class FakeAgent {
    private final JsonCodec json = new JsonCodec(new ObjectMapper());
    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Map<String, String> accepted = new ConcurrentHashMap<>();
    private final AtomicInteger submitAttempts = new AtomicInteger();
    private volatile boolean alwaysUnavailable;
    private volatile boolean holdResponse;
    private volatile CountDownLatch firstAccepted = new CountDownLatch(1);
    private volatile CountDownLatch releaseResponse = new CountDownLatch(1);
    private volatile CountDownLatch solverFinished = new CountDownLatch(1);

    FakeAgent() {
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/worker/v1/jobs/submit", this::submit);
        server.start();
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
    }

    String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
      solverFinished.countDown();
      accepted.clear();
      submitAttempts.set(0);
      alwaysUnavailable = false;
      holdResponse = false;
      firstAccepted = new CountDownLatch(1);
      releaseResponse = new CountDownLatch(1);
      solverFinished = new CountDownLatch(1);
    }

    void close() {
      releaseResponse.countDown();
      solverFinished.countDown();
      server.stop(0);
      threads.shutdownNow();
    }

    private void submit(HttpExchange exchange) throws IOException {
      submitAttempts.incrementAndGet();
      Map<String, Object> request =
          json.readMap(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String requestId = request.get("executionRequestId").toString();
      String external =
          accepted.computeIfAbsent(
              requestId,
              ignored -> {
                CountDownLatch completion = solverFinished;
                threads.submit(
                    () -> {
                      try {
                        completion.await();
                      } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                      }
                    });
                return "fake-" + UUID.randomUUID();
              });
      firstAccepted.countDown();
      if (holdResponse) {
        try {
          releaseResponse.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] body =
          json.write(
                  alwaysUnavailable
                      ? Map.of("detail", "controlled uncertainty after starting solver")
                      : Map.of(
                          "accepted", true, "externalExecutionId", external, "state", "RUNNING"))
              .getBytes(StandardCharsets.UTF_8);
      try {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(alwaysUnavailable ? 503 : 202, body.length);
        exchange.getResponseBody().write(body);
      } finally {
        exchange.close();
      }
    }
  }
}
