package com.example.cae.result;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.common.SqlRows;
import com.example.cae.messaging.CommandEnvelope;
import com.example.cae.messaging.InboxRepository;
import com.example.cae.outbox.OutboxPublisher;
import com.example.cae.outbox.OutboxRepository;
import com.example.cae.report.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real MySQL, Rabbit and HTTP to a real Matplotlib/python-pptx Worker. */
@SpringBootTest(
    properties = {
      "cae.mq.listeners-enabled=true",
      "cae.mq.max-delivery-attempts=3",
      "cae.mq.retry-base-ms=100"
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PipelineIntegrationTest extends ResultTestSupport {
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private static final int PORT = port();
  private static final Process WORKER = start();

  @DynamicPropertySource
  static void worker(DynamicPropertyRegistry properties) {
    properties.add("WORKER_URLS", () -> "node-a=http://127.0.0.1:" + PORT);
    properties.add("SERVICE_TOKEN", () -> "demo-service-token");
  }

  @Autowired ResultParsingService parsing;
  @Autowired PipelineService pipeline;
  @Autowired PipelineRepository repository;
  @Autowired ReportApplicationService reports;
  @Autowired RenderFingerprint fingerprints;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxPublisher publisher;
  @Autowired InboxRepository inbox;
  @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;

  private static int port() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static Process start() {
    try {
      Path repo = Path.of("..").toAbsolutePath().normalize();
      Path python = repo.resolve("worker/.venv/bin/python");
      if (!Files.isExecutable(python)) python = repo.resolve(".venv/bin/python");
      var process =
          new ProcessBuilder(
              python.toString(),
              "-c",
              "import os,uvicorn;from pathlib import Path;from app.main import create_app;from app.settings import Settings;r=Path(os.environ['DATA_ROOT']);uvicorn.run(create_app(Settings(r,r/'agents/node-a/registry.sqlite3')),host='127.0.0.1',port=int(os.environ['CAE_RENDER_TEST_PORT']),log_level='warning')");
      process.directory(repo.resolve("worker").toFile());
      process.environment().put("DATA_ROOT", ROOT.resolve("data").toString());
      process.environment().put("CAE_RENDER_TEST_PORT", Integer.toString(PORT));
      process.redirectErrorStream(true);
      process.redirectOutput(Files.createTempFile("cae-render-worker-", ".log").toFile());
      var child = process.start();
      for (int attempt = 0; attempt < 100; attempt++) {
        try {
          if (HTTP.send(
                      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/health"))
                          .GET()
                          .build(),
                      HttpResponse.BodyHandlers.discarding())
                  .statusCode()
              == 200) return child;
        } catch (Exception notReady) {
          if (!child.isAlive()) throw new IllegalStateException("Render test Worker exited");
        }
        Thread.sleep(100);
      }
      child.destroyForcibly();
      throw new IllegalStateException("Render test Worker did not start");
    } catch (Exception error) {
      throw new IllegalStateException(
          "Run scripts/bootstrap.sh to install rendering test dependencies", error);
    }
  }

  @AfterAll
  static void stop() throws Exception {
    WORKER.destroy();
    if (!WORKER.waitFor(3, TimeUnit.SECONDS)) WORKER.destroyForcibly();
  }

  private CommandEnvelope command(Fixture fixture, String type, String version) {
    return jdbc
        .query(
            "SELECT payload FROM cae_outbox_event WHERE aggregate_id=? AND event_type=? AND exchange_name='cae.command' ORDER BY id DESC",
            (row, index) -> CommandEnvelope.from(json.readMap(row.getString(1))),
            fixture.jobId(),
            type)
        .stream()
        .filter(row -> version.equals(row.payload().get("parserVersion")))
        .findFirst()
        .orElseThrow();
  }

  private void parsed(Fixture fixture, String version) {
    parsing.parse(fixture.jobId(), fixture.executionId(), version);
  }

  private void finish(Fixture fixture, String version) {
    pipeline.process(command(fixture, "POST_PROCESS_REQUESTED", version), "POST");
    pipeline.process(command(fixture, "REPORT_REQUESTED", version), "REPORT");
  }

  private void fault(String stage, int count) throws Exception {
    var response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + PORT + "/worker/v1/faults/render"))
                .header("X-Service-Token", "demo-service-token")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.write(Map.of("stage", stage, "failuresRemaining", count))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void completeRealModalReportKeepsAllKpisImagesAndDraftIdentity() throws Exception {
    var fixture = completed("STRUCTURE", "BIW_MODAL", 2);
    parsed(fixture, "v1");
    finish(fixture, "v1");
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    var records = repository.reports(fixture.jobId(), null, "engineer", false);
    assertThat(records).hasSize(1);
    var report = records.get(0);
    assertThat(report).containsEntry("reportStatus", "DRAFT").containsEntry("postVersion", 1);
    var artifact =
        jdbc.query(
                "SELECT * FROM cae_artifact WHERE id=?", SqlRows.MAPPER, report.get("artifactId"))
            .get(0);
    Path file = ROOT.resolve("data").resolve(artifact.get("uri").toString());
    try (ZipFile zip = new ZipFile(file.toFile())) {
      var slide =
          zip.stream()
              .filter(row -> row.getName().matches("ppt/slides/slide[0-9]+\\.xml"))
              .toList();
      assertThat(slide).hasSize(5);
      StringBuilder xml = new StringBuilder();
      for (var entry : slide) {
        String text =
            new String(
                zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("DRAFT / Engineering review required", "BIW_MODAL / Subcase 2");
        xml.append(text);
      }
      assertThat(xml.toString())
          .contains("315.125", "21111.25", "18666.75", "28.6", "31.9", "42.3", "49.8");
      assertThat(zip.stream().filter(row -> row.getName().startsWith("ppt/media/")).count())
          .isEqualTo(4);
    }
    assertThat(repository.stages(fixture.jobId()))
        .allMatch(row -> row.get("status").equals("COMPLETE"));
    finish(fixture, "v1");
    assertThat(repository.reports(fixture.jobId(), null, "engineer", false)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_outbox_event WHERE aggregate_id=? AND event_type='REPORT_REQUESTED' AND exchange_name='cae.command'",
                Integer.class,
                fixture.jobId()))
        .isEqualTo(1);
  }

  @Test
  void reviewAndApprovalAreProjectScopedManualAndIdempotent() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    finish(fixture, "v1");
    long id = SqlRows.id(repository.reports(fixture.jobId(), null, "engineer", false).get(0));
    mvc.perform(
            post("/api/v1/reports/" + id + "/review").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/reports/" + id + "/approve").with(httpBasic("lead", "lead123")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/reports/" + id + "/approve").with(httpBasic("admin", "admin123")))
        .andExpect(status().isConflict());
    for (int repeat = 0; repeat < 2; repeat++)
      mvc.perform(post("/api/v1/reports/" + id + "/review").with(httpBasic("lead", "lead123")))
          .andExpect(status().isOk());
    for (int repeat = 0; repeat < 2; repeat++)
      mvc.perform(post("/api/v1/reports/" + id + "/approve").with(httpBasic("admin", "admin123")))
          .andExpect(status().isOk());
    assertThat(repository.report(id, false))
        .containsEntry("reportStatus", "APPROVED")
        .containsEntry("reviewer", "lead")
        .containsEntry("approver", "admin");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_audit_log WHERE target_type='REPORT' AND target_id=?",
                Integer.class,
                Long.toString(id)))
        .isEqualTo(2);
  }

  @Test
  void v2HistoricalFailureCannotUndoApprovedV1OrSubmitAnotherSolver() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    finish(fixture, "v1");
    var original = repository.reports(fixture.jobId(), null, "engineer", false).get(0);
    long originalId = SqlRows.id(original);
    mvc.perform(
            post("/api/v1/reports/" + originalId + "/review").with(httpBasic("lead", "lead123")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/reports/" + originalId + "/approve").with(httpBasic("admin", "admin123")))
        .andExpect(status().isOk());
    var old =
        Files.readAllBytes(
            ROOT.resolve("data")
                .resolve(
                    jdbc.queryForObject(
                        "SELECT uri FROM cae_artifact WHERE id=?",
                        String.class,
                        original.get("artifactId"))));
    parsed(fixture, "v2");
    fault("post", 1);
    assertThatThrownBy(
            () -> pipeline.process(command(fixture, "POST_PROCESS_REQUESTED", "v2"), "POST"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
    finish(fixture, "v2");
    assertThat(repository.report(originalId, false)).containsEntry("reportStatus", "APPROVED");
    assertThat(repository.reports(fixture.jobId(), null, "engineer", false)).hasSize(2);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(
            Files.readAllBytes(
                ROOT.resolve("data")
                    .resolve(
                        jdbc.queryForObject(
                            "SELECT uri FROM cae_artifact WHERE id=?",
                            String.class,
                            original.get("artifactId")))))
        .isEqualTo(old);
  }

  @Test
  void postFailureRetriesTheSameFrozenStageAndExecution() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    fault("post", 1);
    assertThatThrownBy(
            () -> pipeline.process(command(fixture, "POST_PROCESS_REQUESTED", "v1"), "POST"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESS_FAILED");
    var original = repository.stages(fixture.jobId()).get(0);
    var execution = executions.find(fixture.executionId());
    for (int repeat = 0; repeat < 2; repeat++)
      mvc.perform(
              post("/api/v1/jobs/" + fixture.jobNo() + "/retry")
                  .with(httpBasic("engineer", "engineer123"))
                  .contentType("application/json")
                  .content("{\"idempotencyKey\":\"same-post-retry\"}"))
          .andExpect(status().isAccepted());
    finish(fixture, "v1");
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
    assertThat(repository.stages(fixture.jobId()).get(0))
        .containsEntry("id", original.get("id"))
        .containsEntry("requestFingerprint", original.get("requestFingerprint"));
    assertThat(executions.find(fixture.executionId())).isEqualTo(execution);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  @Test
  void reportRetryPreservesCompletedImagesAndTheirModificationTimes() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    pipeline.process(command(fixture, "POST_PROCESS_REQUESTED", "v1"), "POST");
    var images =
        jdbc.query(
            "SELECT uri,checksum FROM cae_artifact WHERE job_id=? AND artifact_type='IMAGE' ORDER BY id",
            SqlRows.MAPPER,
            fixture.jobId());
    Map<String, java.nio.file.attribute.FileTime> times = new LinkedHashMap<>();
    for (var row : images)
      times.put(
          row.get("uri").toString(),
          Files.getLastModifiedTime(ROOT.resolve("data").resolve(row.get("uri").toString())));
    fault("report", 1);
    assertThatThrownBy(() -> pipeline.process(command(fixture, "REPORT_REQUESTED", "v1"), "REPORT"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "REPORT_FAILED");
    var original = repository.stages(fixture.jobId()).get(1);
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/retry")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"idempotencyKey\":\"report-retry\"}"))
        .andExpect(status().isAccepted());
    pipeline.process(command(fixture, "REPORT_REQUESTED", "v1"), "REPORT");
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
    assertThat(repository.stages(fixture.jobId()).get(1))
        .containsEntry("id", original.get("id"))
        .containsEntry("requestFingerprint", original.get("requestFingerprint"));
    assertThat(
            jdbc.query(
                "SELECT uri,checksum FROM cae_artifact WHERE job_id=? AND artifact_type='IMAGE' ORDER BY id",
                SqlRows.MAPPER,
                fixture.jobId()))
        .isEqualTo(images);
    for (var row : times.entrySet())
      assertThat(Files.getLastModifiedTime(ROOT.resolve("data").resolve(row.getKey())))
          .isEqualTo(row.getValue());
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  private long append(CommandEnvelope command, String route) {
    tx.executeWithoutResult(
        status -> outbox.append(command, "cae.command", route, java.time.Instant.now()));
    return jdbc.queryForObject(
        "SELECT id FROM cae_outbox_event WHERE event_id=?", Long.class, command.deliveryId());
  }

  private void publishStages(Fixture fixture) {
    var ids =
        jdbc.queryForList(
            "SELECT id FROM cae_outbox_event WHERE aggregate_id=? AND exchange_name='cae.command' AND event_type IN ('POST_PROCESS_REQUESTED','REPORT_REQUESTED') AND status IN ('NEW','RETRY')",
            Long.class,
            fixture.jobId());
    publisher.publishIds(ids);
  }

  private String handled(CommandEnvelope command, String consumer) {
    var status =
        jdbc.queryForList(
            "SELECT status FROM cae_message_inbox WHERE logical_event_id=? AND consumer_name=?",
            String.class,
            command.eventId(),
            consumer);
    return status.isEmpty() ? null : status.get(0);
  }

  @Test
  void actualRabbitDuplicatePostAndReportDeliveriesAckOneStageAndOneDraft() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    var original = command(fixture, "POST_PROCESS_REQUESTED", "v1");
    append(original.retry(0), "job.postprocess");
    var different =
        CommandEnvelope.create(
            original.eventType(), fixture.jobId(), fixture.jobNo(), original.payload());
    append(different, "job.postprocess");
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(25))
        .untilAsserted(
            () -> {
              publishStages(fixture);
              assertThat(handled(original, "post-process-v1")).isEqualTo("DONE");
              assertThat(handled(different, "post-process-v1")).isEqualTo("DONE");
              assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
            });
    var report = command(fixture, "REPORT_REQUESTED", "v1");
    double count = metrics.counter("cae.mq.stage.acked", "stage", "REPORT").count();
    append(report.retry(0), "job.report");
    var another =
        CommandEnvelope.create(
            report.eventType(), fixture.jobId(), fixture.jobNo(), report.payload());
    append(another, "job.report");
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              publishStages(fixture);
              assertThat(handled(another, "draft-report-v1")).isEqualTo("DONE");
              assertThat(metrics.counter("cae.mq.stage.acked", "stage", "REPORT").count())
                  .isGreaterThanOrEqualTo(count + 2);
            });
    assertThat(repository.stages(fixture.jobId())).hasSize(2);
    assertThat(repository.reports(fixture.jobId(), null, "engineer", false)).hasSize(1);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_artifact WHERE job_id=?", Integer.class, fixture.jobId()))
        .isEqualTo(11);
  }

  @Test
  void actualRabbitRenderFailureIsBoundedAndManualRetryKeepsTheSolver() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    var original = command(fixture, "POST_PROCESS_REQUESTED", "v1");
    fault("post", 3);
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              publishStages(fixture);
              assertThat(handled(original, "post-process-v1")).isEqualTo("DEAD");
            });
    var dead = rabbit.receive("cae.job.postprocess.dlq", 3000);
    assertThat(dead).isNotNull();
    assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    var event =
        CommandEnvelope.from(
            json.readMap(new String(dead.getBody(), java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(event.eventId()).isEqualTo(original.eventId());
    assertThat(event.deliveryAttempt()).isEqualTo(2);
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESS_FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT retry_count FROM cae_message_inbox WHERE logical_event_id=? AND consumer_name='post-process-v1'",
                Integer.class,
                original.eventId()))
        .isEqualTo(3);
    var execution = executions.find(fixture.executionId());
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/retry")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"idempotencyKey\":\"retry-after-dlq\"}"))
        .andExpect(status().isAccepted());
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              publishStages(fixture);
              assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
            });
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(executions.find(fixture.executionId())).isEqualTo(execution);
    assertThat(repository.reports(fixture.jobId(), null, "engineer", false)).hasSize(1);
  }

  @Test
  void explicitBaselineAndGenerationKeysRetainOriginalReportAndSource() throws Exception {
    var baseline = completed();
    parsed(baseline, "v1");
    finish(baseline, "v1");
    var fixture = completed();
    parsed(fixture, "v1");
    finish(fixture, "v1");
    var old = repository.reports(fixture.jobId(), null, "engineer", false).get(0);
    String request =
        json.write(
            Map.of(
                "parserVersion",
                "v1",
                "baselineJobNo",
                baseline.jobNo(),
                "idempotencyKey",
                "new-draft"));
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/reports")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(request))
        .andExpect(status().isForbidden());
    for (int repeat = 0; repeat < 2; repeat++)
      mvc.perform(
              post("/api/v1/jobs/" + fixture.jobNo() + "/reports")
                  .with(httpBasic("lead", "lead123"))
                  .contentType("application/json")
                  .content(request))
          .andExpect(status().isAccepted());
    finish(fixture, "v1");
    assertThat(repository.reports(fixture.jobId(), null, "engineer", false)).hasSize(2);
    var latest = repository.reports(fixture.jobId(), null, "engineer", false).get(0);
    assertThat(latest)
        .containsEntry("reportVersion", 2)
        .containsEntry("baselineJobNo", baseline.jobNo());
    assertThat(repository.report(SqlRows.id(old), false))
        .containsEntry("artifactId", old.get("artifactId"));
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
    String changed = json.write(Map.of("parserVersion", "v2", "idempotencyKey", "new-draft"));
    parsed(fixture, "v2");
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/reports")
                .with(httpBasic("lead", "lead123"))
                .contentType("application/json")
                .content(changed))
        .andExpect(status().isConflict());
  }

  @Test
  void lateV1RenderAndReportFailureCannotReplaceActivatedV2AndRpcHoldsNoJobLock() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    var response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + PORT + "/worker/v1/faults/render"))
                .header("X-Service-Token", "demo-service-token")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.write(
                            Map.of(
                                "stage",
                                "post",
                                "failuresRemaining",
                                0,
                                "delaySeconds",
                                3,
                                "jobNo",
                                fixture.jobNo()))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var callers = Executors.newSingleThreadExecutor();
    try {
      var old =
          callers.submit(
              () -> pipeline.process(command(fixture, "POST_PROCESS_REQUESTED", "v1"), "POST"));
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(repository.stages(fixture.jobId()))
                      .anyMatch(row -> row.get("status").equals("RUNNING")));
      long started = System.nanoTime();
      tx.executeWithoutResult(
          status -> {
            var job = jobs.lockById(fixture.jobId());
            jobs.transition(
                job,
                com.example.cae.job.domain.JobStatus.POST_PROCESS_FAILED,
                "CONTROLLED_STAGE_FAILURE",
                Map.of("test", true));
          });
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
      parsed(fixture, "v2");
      long active =
          ((Number) jobs.findById(fixture.jobId()).get("activePipelineParseId")).longValue();
      assertThat(repository.parse(active)).containsEntry("parserVersion", "v2");
      fault("post", 0);
      finish(fixture, "v2");
      old.get(15, TimeUnit.SECONDS);
      assertThat(jobs.findById(fixture.jobId()))
          .containsEntry("status", "SUCCESS")
          .containsEntry("activePipelineParseId", active);
      fault("report", 1);
      assertThatThrownBy(
              () -> pipeline.process(command(fixture, "REPORT_REQUESTED", "v1"), "REPORT"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(jobs.findById(fixture.jobId()))
          .containsEntry("status", "SUCCESS")
          .containsEntry("activePipelineParseId", active);
      pipeline.process(command(fixture, "REPORT_REQUESTED", "v1"), "REPORT");
      assertThat(repository.reports(fixture.jobId(), null, "engineer", false)).hasSize(2);
      assertThat(executions.history(fixture.jobId())).hasSize(1);
    } finally {
      fault("post", 0);
      fault("report", 0);
      callers.shutdownNow();
    }
  }

  @Test
  void repeatedGenerationKeyDuringRecoveryReturnsTheOriginalActiveStage() throws Exception {
    var fixture = completed();
    parsed(fixture, "v1");
    fault("post", 1);
    assertThatThrownBy(
            () -> pipeline.process(command(fixture, "POST_PROCESS_REQUESTED", "v1"), "POST"))
        .isInstanceOf(IllegalArgumentException.class);
    String body = "{\"parserVersion\":\"v1\",\"idempotencyKey\":\"recovery-generation\"}";
    String first =
        mvc.perform(
                post("/api/v1/jobs/" + fixture.jobNo() + "/reports")
                    .with(httpBasic("engineer", "engineer123"))
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    String repeated =
        mvc.perform(
                post("/api/v1/jobs/" + fixture.jobNo() + "/reports")
                    .with(httpBasic("engineer", "engineer123"))
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readMap(repeated).get("id")).isEqualTo(json.readMap(first).get("id"));
    finish(fixture, "v1");
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "SUCCESS");
    assertThat(repository.stages(fixture.jobId())).hasSize(3);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }
}
