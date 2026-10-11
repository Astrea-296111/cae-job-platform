package com.example.cae.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.artifact.ArtifactFileStore;
import com.example.cae.artifact.ArtifactRepository;
import com.example.cae.common.PlatformFailure;
import com.example.cae.common.SqlRows;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** Real MySQL uniqueness, input integrity and authenticated streaming artifact delivery. */
@SpringBootTest(properties = "cae.mq.listeners-enabled=false")
@AutoConfigureMockMvc
class ResultParsingIntegrationTest extends ResultTestSupport {
  @Autowired ResultParsingService parsing;
  @Autowired ResultRepository results;
  @Autowired ObjectMapper mapper;
  @Autowired ArtifactFileStore files;
  @Autowired ArtifactRepository artifacts;

  @Test
  void concurrentDifferentRequestsForSameParserVersionPersistOneMetricSetAndPostCommand()
      throws Exception {
    Fixture fixture = completed();
    var callers = Executors.newFixedThreadPool(8);
    try {
      List<Callable<ResultParsingService.ParseOutcome>> requests = new ArrayList<>();
      for (int i = 0; i < 16; i++)
        requests.add(() -> parsing.parse(fixture.jobId(), fixture.executionId(), "v1"));
      int applied = 0;
      Set<Long> recordIds = new HashSet<>();
      for (var future : callers.invokeAll(requests)) {
        var outcome = future.get();
        assertThat(outcome.stale()).isFalse();
        if (outcome.applied()) applied++;
        recordIds.add(SqlRows.id(outcome.record()));
      }
      assertThat(applied).isEqualTo(1);
      assertThat(recordIds).hasSize(1);
    } finally {
      callers.shutdownNow();
    }
    assertThat(results.history(fixture.jobId())).hasSize(1);
    assertThat(results.metrics(fixture.jobId(), "v1")).hasSize(7);
    assertThat(postCommands(fixture)).isEqualTo(1);
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow())
        .containsEntry("status", "COMPLETE")
        .containsEntry("conditionCode", "BIW_TORSION")
        .containsEntry("subcase", 3)
        .containsEntry("rawChecksum", sha(fixture.workspace().resolve("raw_result.json")));
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  @Test
  void explicitV2RetainsV1MetricsAndArtifactsAndDoesNotCreateAnotherExecution() throws Exception {
    Fixture fixture = completed();
    parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    List<Map<String, Object>> originalMetrics = results.metrics(fixture.jobId(), "v1");
    List<Map<String, Object>> originalArtifacts = artifactRows(fixture);
    Map<String, Object> originalRecord = results.find(fixture.jobId(), "v1").orElseThrow();

    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/reparse")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"parserVersion\":\"v2\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/reparse")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"parserVersion\":\"v2\"}"))
        .andExpect(status().isAccepted());
    assertThat(results.find(fixture.jobId(), "v2").orElseThrow())
        .containsEntry("status", "REQUESTED");
    assertThat(parseCommands(fixture, "v2")).isEqualTo(1);
    parsing.parse(fixture.jobId(), fixture.executionId(), "v2");

    assertThat(results.history(fixture.jobId())).hasSize(2);
    assertThat(results.metrics(fixture.jobId(), null)).hasSize(14);
    assertThat(results.metrics(fixture.jobId(), "v1")).isEqualTo(originalMetrics);
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow()).isEqualTo(originalRecord);
    assertThat(artifactRows(fixture)).containsAll(originalArtifacts);
    assertThat(results.metrics(fixture.jobId(), "v2"))
        .extracting(m -> m.get("metricCode"))
        .containsExactlyInAnyOrderElementsOf(
            originalMetrics.stream().map(m -> m.get("metricCode")).toList());
    assertThat(postCommands(fixture)).isEqualTo(2);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  @ParameterizedTest
  @CsvSource({
    "STRUCTURE, BIW_TORSION, 7, COMPLETE",
    "NVH, NVH_FRF, 2, PILOT",
    "CRASH, CRASH_FRONTAL, 3, PILOT",
    "CFD, CFD_DRAG, 4, PILOT"
  })
  void eachDisciplinePersistsCanonicalValuesWithConditionAndSubcase(
      String analysis, String condition, int expectedCount, String maturity) throws Exception {
    Fixture fixture = completed(analysis, condition, 7);
    var outcome = parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    assertThat(outcome.applied()).isTrue();
    assertThat(outcome.record())
        .containsEntry("maturity", maturity)
        .containsEntry("metricCount", expectedCount);
    List<Map<String, Object>> metrics = results.metrics(fixture.jobId(), "v1");
    assertThat(metrics).hasSize(expectedCount);
    assertThat(metrics)
        .allSatisfy(
            m ->
                assertThat(m)
                    .containsEntry("conditionCode", condition)
                    .containsEntry("subcase", 7)
                    .containsEntry("parserVersion", "v1")
                    .containsEntry("status", "VALID"));
    if (analysis.equals("STRUCTURE"))
      assertThat((BigDecimal) metric(metrics, "MASS").get("valueNum"))
          .isEqualByComparingTo("315.125");
    if (analysis.equals("CFD"))
      assertThat(metric(metrics, "CFD_CONVERGED"))
          .containsEntry("valueNum", null)
          .containsEntry("valueText", "true");
    assertThat(postCommands(fixture)).isEqualTo(1);
  }

  @Test
  void lateOldExecutionParseIsNoOpBeforeOpeningFilesOrMutatingCurrentAttempt() throws Exception {
    Fixture fixture = completed();
    // Materialize a historical replacement attempt through the real allocation ledger.
    jdbc.update(
        "UPDATE cae_job SET status='READY',current_execution_id=NULL WHERE id=?", fixture.jobId());
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/submit")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(
                    json.write(
                        Map.of("idempotencyKey", "replacement-" + java.util.UUID.randomUUID()))))
        .andExpect(status().isAccepted());
    long current = allocation.allocate(fixture.jobId()).orElseThrow();
    assertThat(current).isNotEqualTo(fixture.executionId());
    Files.delete(fixture.workspace().resolve("raw_result.json"));
    var outcome = parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    assertThat(outcome.stale()).isTrue();
    assertThat(outcome.applied()).isFalse();
    assertThat(results.history(fixture.jobId())).isEmpty();
    assertThat(results.metrics(fixture.jobId(), null)).isEmpty();
    assertThat(postCommands(fixture)).isZero();
    assertThat(jobs.findById(fixture.jobId()))
        .containsEntry("status", "SUBMITTING")
        .containsEntry("currentExecutionId", current);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schema",
        "condition",
        "subcase",
        "configRequest",
        "stateExternal",
        "statePid",
        "snapshot",
        "rawSymlink",
        "workspace",
        "numericType",
        "numericStorageOverflow",
        "configExtraSeed",
        "immutableRequestJob",
        "immutableRequestIdentity"
      })
  void invalidSchemaIdentitySnapshotOrPathNeverProducesMetricsOrPostCommand(String corruption)
      throws Exception {
    Fixture fixture = completed();
    switch (corruption) {
      case "schema" -> rewrite(fixture, "raw_result.json", "schemaVersion", 999);
      case "condition" -> rewrite(fixture, "raw_result.json", "conditionCode", "OTHER_CONDITION");
      case "subcase" -> rewrite(fixture, "raw_result.json", "subcase", 9);
      case "configRequest" ->
          rewrite(fixture, "execution-config.json", "executionRequestId", "wrong-request");
      case "stateExternal" ->
          rewrite(fixture, "state.json", "externalExecutionId", "wrong-external");
      case "statePid" -> rewrite(fixture, "state.json", "pid", 9999);
      case "snapshot" -> {
        Path material =
            Path.of(jobs.findById(fixture.jobId()).get("workspace").toString())
                .resolve("input/materials.inc");
        Files.setPosixFilePermissions(
            material, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.writeString(material, "TAMPERED AFTER VALIDATION\n");
      }
      case "rawSymlink" -> {
        Path outside =
            fixture
                .workspace()
                .getParent()
                .resolve("outside-" + java.util.UUID.randomUUID() + ".json");
        Files.move(fixture.workspace().resolve("raw_result.json"), outside);
        Files.createSymbolicLink(fixture.workspace().resolve("raw_result.json"), outside);
      }
      case "workspace" ->
          jdbc.update(
              "UPDATE cae_job_execution SET workspace=? WHERE id=?",
              ROOT.resolve("untrusted").toString(),
              fixture.executionId());
      case "numericType" -> rewrite(fixture, "raw_result.json", "mass_kg", "315.125");
      case "numericStorageOverflow" ->
          rewrite(fixture, "raw_result.json", "mass_kg", new BigDecimal("1e100"));
      case "configExtraSeed" -> {
        Map<String, Object> config =
            json.readMap(Files.readString(fixture.workspace().resolve("execution-config.json")));
        Map<String, Object> mock = new LinkedHashMap<>(json.readMap(config.get("mock")));
        mock.put("seed", 999);
        rewrite(fixture, "execution-config.json", "mock", mock);
        rewrite(fixture, "raw_result.json", "seed", 999);
      }
      case "immutableRequestJob", "immutableRequestIdentity" -> {
        String field = corruption.equals("immutableRequestJob") ? "jobNo" : "executionRequestId";
        String altered = field.equals("jobNo") ? "CAE-20000101-000001" : "forged-request";
        Map<String, Object> request =
            new LinkedHashMap<>(
                json.readMap(executions.find(fixture.executionId()).get("request")));
        request.put(field, altered);
        jdbc.update(
            "UPDATE cae_job_execution SET request_json=? WHERE id=?",
            json.write(request),
            fixture.executionId());
        rewrite(fixture, "execution-config.json", field, altered);
      }
      default -> throw new IllegalArgumentException("Unknown test corruption");
    }
    assertThatThrownBy(() -> parsing.parse(fixture.jobId(), fixture.executionId(), "v1"))
        .isInstanceOf(
            corruption.equals("snapshot") ? PlatformFailure.class : IllegalArgumentException.class);
    assertThat(results.metrics(fixture.jobId(), null)).isEmpty();
    assertThat(postCommands(fixture)).isZero();
    assertThat(jobs.findById(fixture.jobId()))
        .containsEntry("status", "POST_PROCESS_FAILED")
        .containsEntry("failureCode", "POST_PROCESS_FAILED");
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow()).containsEntry("status", "FAILED");
  }

  @Test
  void sameVersionCannotOverwriteAlreadyStoredRawChecksumOrMetricValues() throws Exception {
    Fixture fixture = completed();
    parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    Map<String, Object> record = results.find(fixture.jobId(), "v1").orElseThrow();
    List<Map<String, Object>> original = results.metrics(fixture.jobId(), "v1");
    rewrite(fixture, "raw_result.json", "mass_kg", 999.999);
    assertThatThrownBy(() -> parsing.parse(fixture.jobId(), fixture.executionId(), "v1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(results.metrics(fixture.jobId(), "v1")).isEqualTo(original);
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow()).isEqualTo(record);
    assertThat(postCommands(fixture)).isEqualTo(1);
  }

  @Test
  void ownerCanRepairAndReparseAFailedStageWithoutAnotherSolverAttempt() throws Exception {
    Fixture fixture = completed();
    rewrite(fixture, "raw_result.json", "schemaVersion", 999);
    assertThatThrownBy(() -> parsing.parse(fixture.jobId(), fixture.executionId(), "v1"))
        .isInstanceOf(IllegalArgumentException.class);
    long originalRecordId = SqlRows.id(results.find(fixture.jobId(), "v1").orElseThrow());
    rewrite(fixture, "raw_result.json", "schemaVersion", 1);
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/reparse")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"parserVersion\":\"v1\"}"))
        .andExpect(status().isAccepted());
    parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow())
        .containsEntry("id", originalRecordId)
        .containsEntry("status", "COMPLETE");
    assertThat(results.metrics(fixture.jobId(), "v1")).hasSize(7);
    assertThat(jobs.findById(fixture.jobId()))
        .containsEntry("status", "POST_PROCESSING")
        .containsEntry("failureCode", null);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    assertThat(postCommands(fixture)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CANCELLED", "RUNTIME_FAILED", "SUBMIT_FAILED", "READY"})
  void obsoleteParseCommandCannotMoveAJobOutsideTheParseStages(String status) throws Exception {
    Fixture fixture = completed();
    jdbc.update("UPDATE cae_job SET status=? WHERE id=?", status, fixture.jobId());
    Map<String, Object> before = jobs.findById(fixture.jobId());
    Files.delete(fixture.workspace().resolve("raw_result.json"));
    var outcome = parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    assertThat(outcome.stale()).isTrue();
    assertThat(outcome.applied()).isFalse();
    assertThat(jobs.findById(fixture.jobId())).isEqualTo(before);
    assertThat(results.history(fixture.jobId())).isEmpty();
    assertThat(postCommands(fixture)).isZero();
  }

  @Test
  void parseCommandReconcilesKnownSuccessDuringCancellationWithoutLosingTheStageCommand()
      throws Exception {
    Fixture fixture = completed();
    jdbc.update("UPDATE cae_job SET status='CANCEL_REQUESTED' WHERE id=?", fixture.jobId());
    jdbc.update(
        "UPDATE cae_job_execution SET cancel_requested=TRUE WHERE id=?", fixture.executionId());
    List<Map<String, Object>> ledger =
        jdbc.query(
            "SELECT node_id,cpu_used,cpu_reserved,memory_used_gb,memory_reserved_gb FROM"
                + " cae_compute_node ORDER BY node_id",
            com.example.cae.common.SqlRows.MAPPER);
    assertThat(parsing.parse(fixture.jobId(), fixture.executionId(), "v1").applied()).isTrue();
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(parsing.parse(fixture.jobId(), fixture.executionId(), "v1").applied()).isFalse();
    assertThat(postCommands(fixture)).isEqualTo(1);
    assertThat(results.history(fixture.jobId())).hasSize(1);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
    Map<String, Object> execution = executions.find(fixture.executionId());
    assertThat(execution).containsEntry("status", "SUCCESS");
    Object sticky = execution.get("cancelRequested");
    assertThat(
            Boolean.TRUE.equals(sticky) || sticky instanceof Number value && value.intValue() == 1)
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM cae_resource_reservation WHERE execution_id=?",
                String.class,
                fixture.executionId()))
        .isEqualTo("RELEASED");
    assertThat(
            jdbc.query(
                "SELECT node_id,cpu_used,cpu_reserved,memory_used_gb,memory_reserved_gb FROM"
                    + " cae_compute_node ORDER BY node_id",
                com.example.cae.common.SqlRows.MAPPER))
        .isEqualTo(ledger);
  }

  @Test
  void workerMayNormalizeAnAbsentMockSeedToNullWithoutChangingManifestDerivedSeed()
      throws Exception {
    Fixture fixture = completed();
    Map<String, Object> config =
        json.readMap(Files.readString(fixture.workspace().resolve("execution-config.json")));
    Map<String, Object> mock = new LinkedHashMap<>(json.readMap(config.get("mock")));
    assertThat(mock).doesNotContainKey("seed");
    mock.put("seed", null);
    rewrite(fixture, "execution-config.json", "mock", mock);
    assertThat(parsing.parse(fixture.jobId(), fixture.executionId(), "v1").applied()).isTrue();
    assertThat(results.metrics(fixture.jobId(), "v1")).hasSize(7);
    assertThat(postCommands(fixture)).isEqualTo(1);
  }

  @Test
  void metricsReparseAndArtifactEndpointsEnforceProjectAndOwnerAuthorization() throws Exception {
    Fixture fixture = completed();
    parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    mvc.perform(get("/api/v1/jobs/" + fixture.jobNo() + "/metrics"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/jobs/" + fixture.jobNo() + "/metrics").with(httpBasic("lead", "lead123")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/reparse")
                .with(httpBasic("lead", "lead123"))
                .contentType("application/json")
                .content("{\"parserVersion\":\"v2\"}"))
        .andExpect(status().isForbidden());
    jdbc.update("DELETE FROM cae_project_member WHERE project_id=1 AND username='lead'");
    try {
      mvc.perform(
              get("/api/v1/jobs/" + fixture.jobNo() + "/metrics")
                  .with(httpBasic("lead", "lead123")))
          .andExpect(status().isForbidden());
      mvc.perform(
              get("/api/v1/jobs/" + fixture.jobNo() + "/parses").with(httpBasic("lead", "lead123")))
          .andExpect(status().isForbidden());
      mvc.perform(
              get("/api/v1/jobs/" + fixture.jobNo() + "/artifacts")
                  .with(httpBasic("lead", "lead123")))
          .andExpect(status().isForbidden());
      String protectedDownload =
          artifacts.list(fixture.jobId()).get(0).get("downloadUrl").toString();
      mvc.perform(get(protectedDownload).with(httpBasic("lead", "lead123")))
          .andExpect(status().isForbidden());
    } finally {
      jdbc.update(
          "INSERT INTO cae_project_member(project_id,username,member_role)"
              + " VALUES(1,'lead','PROJECT_LEAD')");
    }
    mvc.perform(
            post("/api/v1/jobs/" + fixture.jobNo() + "/reparse")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"parserVersion\":\"unsupported\"}"))
        .andExpect(status().isBadRequest());
    assertThat(results.history(fixture.jobId())).hasSize(1);
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  @Test
  void registeredRawArtifactStreamsExactBytesAndRejectsTamperingTraversalAndWrongJob()
      throws Exception {
    Fixture fixture = completed();
    parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    List<Map<String, Object>> list =
        mapper.readValue(
            mvc.perform(
                    get("/api/v1/jobs/" + fixture.jobNo() + "/artifacts")
                        .with(httpBasic("engineer", "engineer123")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            new TypeReference<>() {});
    Map<String, Object> raw =
        list.stream()
            .filter(a -> a.get("fileName").equals("raw_result.json"))
            .findFirst()
            .orElseThrow();
    long id = SqlRows.id(raw);
    String download = raw.get("downloadUrl").toString();
    assertThat(raw.get("checksum")).isEqualTo(sha(fixture.workspace().resolve("raw_result.json")));
    byte[] bytes =
        mvc.perform(get(download).with(httpBasic("engineer", "engineer123")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(bytes).isEqualTo(Files.readAllBytes(fixture.workspace().resolve("raw_result.json")));
    mvc.perform(get(download)).andExpect(status().isUnauthorized());
    Fixture other = completed();
    mvc.perform(
            get("/api/v1/jobs/" + other.jobNo() + "/artifacts/" + id + "/download")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isNotFound());
    String originalUri = raw.get("uri").toString();
    jdbc.update("UPDATE cae_artifact SET uri='../outside.json' WHERE id=?", id);
    mvc.perform(get(download).with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isConflict());
    jdbc.update("UPDATE cae_artifact SET uri=? WHERE id=?", originalUri, id);
    Files.writeString(fixture.workspace().resolve("raw_result.json"), "{\"tampered\":true}");
    mvc.perform(get(download).with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isConflict());
  }

  private List<Map<String, Object>> artifactRows(Fixture fixture) {
    return jdbc.query(
        "SELECT * FROM cae_artifact WHERE job_id=? ORDER BY id", SqlRows.MAPPER, fixture.jobId());
  }

  @Test
  void openedVerifiedStreamKeepsRegisteredBytesIfTheOriginalInodeIsModified() throws Exception {
    Fixture fixture = completed();
    parsing.parse(fixture.jobId(), fixture.executionId(), "v1");
    Map<String, Object> raw =
        artifacts.list(fixture.jobId()).stream()
            .filter(a -> a.get("fileName").equals("raw_result.json"))
            .findFirst()
            .orElseThrow();
    Path source = fixture.workspace().resolve("raw_result.json");
    byte[] expected = Files.readAllBytes(source);
    try (var verified = files.openVerified(raw)) {
      byte[] mutation = new byte[expected.length];
      java.util.Arrays.fill(mutation, (byte) 'X');
      Files.write(source, mutation);
      assertThat(verified.readAllBytes()).isEqualTo(expected);
    }
  }

  @Test
  void artifactRegistrationRejectsCrossJobExecutionParseAndUriIdentity() throws Exception {
    Fixture first = completed();
    Fixture second = completed();
    parsing.parse(first.jobId(), first.executionId(), "v1");
    parsing.parse(second.jobId(), second.executionId(), "v1");
    var foreign = files.describe(second.workspace().resolve("raw_result.json"));
    Files.writeString(first.workspace().resolve("candidate.json"), "{\"synthetic\":true}");
    var own = files.describe(first.workspace().resolve("candidate.json"));
    long foreignParse = SqlRows.id(results.find(second.jobId(), "v1").orElseThrow());
    Map<String, Object> metadata = Map.of("conditionCode", "BIW_TORSION", "subcase", 3);
    int before = artifacts.list(first.jobId()).size();
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    t ->
                        artifacts.register(
                            first.jobId(),
                            second.executionId(),
                            null,
                            "",
                            0,
                            "RAW_RESULT",
                            own,
                            "application/json",
                            metadata)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    t ->
                        artifacts.register(
                            first.jobId(),
                            first.executionId(),
                            foreignParse,
                            "v1",
                            0,
                            "METRICS",
                            own,
                            "application/json",
                            metadata)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    t ->
                        artifacts.register(
                            first.jobId(),
                            first.executionId(),
                            null,
                            "",
                            0,
                            "RAW_RESULT",
                            foreign,
                            "application/json",
                            metadata)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(artifacts.list(first.jobId())).hasSize(before);
  }

  private int parseCommands(Fixture fixture, String version) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM cae_outbox_event WHERE aggregate_id=? AND"
            + " event_type='RESULT_PARSE_REQUESTED' AND exchange_name='cae.command' AND"
            + " JSON_UNQUOTE(JSON_EXTRACT(payload,'$.payload.parserVersion'))=?",
        Integer.class,
        fixture.jobId(),
        version);
  }

  private static Map<String, Object> metric(List<Map<String, Object>> metrics, String code) {
    return metrics.stream().filter(m -> m.get("metricCode").equals(code)).findFirst().orElseThrow();
  }
}
