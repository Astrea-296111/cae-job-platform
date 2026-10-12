package com.example.cae.result;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.events.EventStreamService;
import com.example.cae.job.application.JobChanged;
import com.example.cae.job.application.JobQueryCache;
import java.io.*;
import java.net.http.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "cae.mq.listeners-enabled=false")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UiReadModelIntegrationTest extends ResultTestSupport {
  @Autowired JobQueryCache cache;
  @Autowired EventStreamService streams;
  @LocalServerPort int port;

  @Test
  void concurrentCreateHeaderClaimsOneJobAndRejectsChangedBody() throws Exception {
    String key = "ui-create-" + UUID.randomUUID();
    String body =
        "{\"projectId\":1,\"modelRevisionId\":2,\"analysisType\":\"STRUCTURE\",\"conditionCode\":\"BIW_TORSION\"}";
    var callers = Executors.newFixedThreadPool(8);
    try {
      var requests = new ArrayList<Callable<String>>();
      for (int i = 0; i < 16; i++)
        requests.add(
            () ->
                mvc.perform(
                        post("/api/v1/jobs")
                            .with(httpBasic("engineer", "engineer123"))
                            .header("Idempotency-Key", key)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
      Set<Object> numbers = new HashSet<>();
      for (var result : callers.invokeAll(requests))
        numbers.add(json.readMap(result.get()).get("jobNo"));
      assertThat(numbers).hasSize(1);
    } finally {
      callers.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_job_create_request WHERE actor='engineer' AND idempotency_key=? AND job_id IS NOT NULL",
                Integer.class,
                key))
        .isEqualTo(1);
    mvc.perform(
            post("/api/v1/jobs")
                .with(httpBasic("engineer", "engineer123"))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body.replace("BIW_TORSION", "BIW_MODAL")))
        .andExpect(status().isConflict());
  }

  @Test
  void dashboardFiltersPermissionsAndUsesActualQueuedToStartedSample() throws Exception {
    var fixture = completed();
    jdbc.update(
        "UPDATE cae_job SET queued_at=(SELECT started_at-INTERVAL 4 SECOND FROM cae_job_execution WHERE id=?) WHERE id=?",
        fixture.executionId(),
        fixture.jobId());
    var body =
        json.readMap(
            mvc.perform(
                    get("/api/v1/dashboard")
                        .param("projectId", "1")
                        .param("timeZone", "Asia/Shanghai")
                        .with(httpBasic("engineer", "engineer123")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var average = json.readMap(body.get("averageQueueSeconds"));
    assertThat(((Number) average.get("sampleCount")).longValue()).isPositive();
    assertThat(((Number) average.get("value")).doubleValue()).isGreaterThanOrEqualTo(0);
    var rate = json.readMap(body.get("successRate"));
    assertThat(rate).containsKeys("numerator", "denominator", "percent");
    mvc.perform(
            get("/api/v1/dashboard")
                .param("projectId", "1")
                .with(user("outsider").roles("ENGINEER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/dashboard")
                .param("timeZone", "NOT_A_ZONE")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void logTailBoundsRedactsAndChecksExecutionOwnershipAndSymlinks() throws Exception {
    var fixture = completed();
    Files.writeString(
        fixture.workspace().resolve("solver.log"),
        "sample line\n".repeat(30000)
            + "\u001b[31mLine\u001b[0m\nAuthorization: Basic private\npassword=secret demo-service-token\n");
    String body =
        mvc.perform(
                get("/api/v1/jobs/" + fixture.jobNo() + "/logs")
                    .param("lines", "500")
                    .with(httpBasic("engineer", "engineer123")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .doesNotContain("private", "secret", "demo-service-token")
        .contains("[REDACTED]");
    var response = json.readMap(body);
    assertThat((List<?>) response.get("lines")).hasSize(500);
    assertThat(response).containsEntry("truncated", true);
    mvc.perform(
            get("/api/v1/jobs/" + fixture.jobNo() + "/logs")
                .param("lines", "501")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/jobs/" + fixture.jobNo() + "/logs")
                .with(user("outsider").roles("ENGINEER")))
        .andExpect(status().isForbidden());
    var other = completed();
    mvc.perform(
            get("/api/v1/jobs/" + fixture.jobNo() + "/logs")
                .param("executionId", Long.toString(other.executionId()))
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isNotFound());
    var path = fixture.workspace().resolve("solver.log");
    Files.delete(path);
    Files.createSymbolicLink(path, other.workspace().resolve("solver.log"));
    mvc.perform(
            get("/api/v1/jobs/" + fixture.jobNo() + "/logs")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void cacheChecksPermissionBeforeHitAndRejectsOlderVersionFill() throws Exception {
    var fixture = completed();
    var user =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "engineer",
            "ignored",
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_ENGINEER")));
    var first = cache.get(user, fixture.jobNo(), () -> jobs.findById(fixture.jobId()));
    tx.executeWithoutResult(
        status -> jobs.patch(jobs.lockById(fixture.jobId()), Map.of("priority", 77)));
    var fresh = cache.get(user, fixture.jobNo(), () -> jobs.findById(fixture.jobId()));
    assertThat(fresh).containsEntry("priority", 77);
    assertThat(((Number) fresh.get("version")).longValue())
        .isGreaterThan(((Number) first.get("version")).longValue());
    cache.invalidate(
        new JobChanged(fixture.jobNo(), ((Number) fresh.get("version")).longValue() + 1));
    mvc.perform(get("/api/v1/jobs/" + fixture.jobNo()).with(user("outsider").roles("ENGINEER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void realHttpStreamResumesCommittedEventsAndRejectsUnauthorizedScope() throws Exception {
    var fixture = completed();
    long cursor = jdbc.queryForObject("SELECT MAX(id) FROM cae_job_event", Long.class);
    String auth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    "engineer:engineer123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var client = HttpClient.newHttpClient();
    var request =
        HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://localhost:" + port + "/api/v1/events/stream?jobNo=" + fixture.jobNo()))
            .header("Authorization", auth)
            .header("Last-Event-ID", Long.toString(cursor))
            .GET()
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);
    var reader =
        new BufferedReader(
            new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    var reading = Executors.newSingleThreadExecutor();
    try {
      var notification =
          reading.submit(
              () -> {
                StringBuilder received = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                  received.append(line).append('\n');
                  if (line.startsWith("data:") && line.contains("UI_COMMITTED_TEST"))
                    return received.toString();
                }
                return received.toString();
              });
      tx.executeWithoutResult(
          status ->
              jobs.recordEvent(
                  fixture.jobId(), "UI_COMMITTED_TEST", Map.of("secret", "must-not-stream")));
      String received = notification.get(10, TimeUnit.SECONDS);
      assertThat(received)
          .contains("event:ready", "event:job", fixture.jobNo(), "UI_COMMITTED_TEST")
          .doesNotContain("must-not-stream");
    } finally {
      response.body().close();
      reading.shutdownNow();
    }
    // Produce bytes after the TCP peer closes so the server observes and removes the connection.
    for (int i = 0; i < 8 && streams.connectionCount() > 0; i++) {
      tx.executeWithoutResult(
          status -> jobs.recordEvent(fixture.jobId(), "UI_CLOSE_TEST", Map.of()));
      streams.poll();
      Thread.sleep(250);
    }
    assertThat(streams.connectionCount()).isZero();
    mvc.perform(
            get("/api/v1/events/stream")
                .param("jobNo", fixture.jobNo())
                .with(user("outsider").roles("ENGINEER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void onlyAdminCanDisableControlledTemplatesAndChangesAreAudited() throws Exception {
    mvc.perform(
            patch("/api/v1/report-templates/STRUCTURE_DRAFT")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"enabled\":false}"))
        .andExpect(status().isForbidden());
    try {
      mvc.perform(
              patch("/api/v1/report-templates/STRUCTURE_DRAFT")
                  .with(httpBasic("admin", "admin123"))
                  .contentType("application/json")
                  .content("{\"enabled\":false}"))
          .andExpect(status().isOk());
      assertThat(
              jdbc.queryForObject(
                  "SELECT enabled FROM cae_report_template WHERE code='STRUCTURE_DRAFT'",
                  Boolean.class))
          .isFalse();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM cae_audit_log WHERE operation='REPORT_TEMPLATE_UPDATE'",
                  Integer.class))
          .isPositive();
      mvc.perform(
              patch("/api/v1/report-templates/arbitrary-file.pptx")
                  .with(httpBasic("admin", "admin123"))
                  .contentType("application/json")
                  .content("{\"enabled\":false}"))
          .andExpect(status().isNotFound());
    } finally {
      jdbc.update("UPDATE cae_report_template SET enabled=TRUE WHERE code='STRUCTURE_DRAFT'");
    }
  }
}
