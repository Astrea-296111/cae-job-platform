package com.example.cae.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.job.submission.JobAllocationService;
import com.example.cae.job.validation.InputSnapshotService;
import com.example.cae.monitoring.RemoteExecutionStateService;
import com.example.cae.resource.ResourceReservationService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public synthetic files, real validation/snapshot/ledger/state transitions, no commercial Solver.
 */
abstract class ResultTestSupport {
  protected static final Path ROOT = temporaryRoot();

  @DynamicPropertySource
  static void roots(DynamicPropertyRegistry registry) {
    registry.add("CASE_ROOT", () -> ROOT.resolve("cases").toString());
    registry.add("DATA_ROOT", () -> ROOT.resolve("data").toString());
    registry.add("cae.background.enabled", () -> false);
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected JsonCodec json;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected JobRepository jobs;
  @Autowired protected ExecutionRepository executions;
  @Autowired protected JobAllocationService allocation;
  @Autowired protected InputSnapshotService snapshots;
  @Autowired protected RemoteExecutionStateService state;
  @Autowired protected ResourceReservationService resources;
  @Autowired protected TransactionTemplate tx;
  private final List<Long> ownedJobs = new ArrayList<>();
  private final List<Path> ownedSources = new ArrayList<>();
  private List<Map<String, Object>> originalNodes;

  @BeforeEach
  void prepareFreshNode() {
    originalNodes = jdbc.query("SELECT * FROM cae_compute_node", SqlRows.MAPPER);
    jdbc.update("UPDATE cae_compute_node SET drained=TRUE");
    jdbc.update(
        "UPDATE cae_compute_node SET status='ONLINE',drained=FALSE,workspace_reachable=TRUE,"
            + "last_heartbeat=CURRENT_TIMESTAMP(6),observed_cpu_used=0,observed_memory_used_gb=0,"
            + "solver_tags=JSON_ARRAY('STRUCTURE','NVH','CRASH','CFD') WHERE node_id='node-a'");
  }

  @AfterEach
  void releaseOwnedReservationsAndRestoreNodes() throws IOException {
    for (long id : ownedJobs)
      for (Map<String, Object> execution : executions.history(id))
        resources.releaseByExecution(SqlRows.id(execution));
    for (Map<String, Object> node : originalNodes)
      jdbc.update(
          "UPDATE cae_compute_node SET status=?,drained=?,workspace_reachable=?,last_heartbeat=?,"
              + "observed_cpu_used=?,observed_memory_used_gb=?,solver_tags=? WHERE node_id=?",
          node.get("status"),
          node.get("drained"),
          node.get("workspaceReachable"),
          node.get("lastHeartbeat") == null
              ? null
              : Timestamp.from(Instant.parse(node.get("lastHeartbeat").toString())),
          node.get("observedCpuUsed"),
          node.get("observedMemoryUsedGb"),
          node.get("solverTags"),
          node.get("nodeId"));
    for (long id : ownedJobs) {
      Object workspace = jobs.findById(id).get("workspace");
      if (workspace != null) removeOwned(Path.of(workspace.toString()), ROOT.resolve("data/jobs"));
    }
    for (Path source : ownedSources) removeOwned(source, ROOT.resolve("cases"));
    // The harness drops the owned schema/vhost after the suite. Never remove other tests' rows.
    ownedJobs.clear();
    ownedSources.clear();
  }

  protected Fixture completed() throws Exception {
    return completed("STRUCTURE", "BIW_TORSION", 3);
  }

  protected Fixture completed(String analysis, String condition, int subcase) throws Exception {
    String name = "parse-" + UUID.randomUUID();
    Path source = ROOT.resolve("cases").resolve(name);
    Files.createDirectories(source);
    ownedSources.add(source);
    Files.writeString(source.resolve("model.fem"), "$ public synthetic\nINCLUDE 'materials.inc'\n");
    Files.writeString(source.resolve("materials.inc"), "MAT1 SYNTHETIC\n");
    Map<String, Object> manifest =
        Map.of(
            "caseId",
            name,
            "entryDeck",
            "model.fem",
            "includes",
            List.of("materials.inc"),
            "analysisType",
            analysis,
            "conditionCode",
            condition,
            "modelRevision",
            analysis.equals("STRUCTURE") ? "V37" : "P1",
            "parameters",
            Map.of("seed", 37, "thicknessScale", 1, "subcase", subcase));
    Files.writeString(source.resolve("manifest.json"), json.write(manifest));
    String response =
        mvc.perform(
                post("/api/v1/jobs")
                    .with(httpBasic("engineer", "engineer123"))
                    .contentType("application/json")
                    .content(
                        json.write(
                            Map.of(
                                "projectId",
                                analysis.equals("STRUCTURE") ? 1 : 2,
                                "modelRevisionId",
                                analysis.equals("STRUCTURE") ? 2 : 3,
                                "analysisType",
                                analysis,
                                "conditionCode",
                                condition,
                                "casePath",
                                name,
                                "resourceProfile",
                                "SMALL"))))
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
    snapshots.verifySnapshot(jobs.findById(jobId));
    mvc.perform(
            post("/api/v1/jobs/" + number + "/submit")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(Map.of("idempotencyKey", "parse-" + UUID.randomUUID()))))
        .andExpect(status().isAccepted());
    long executionId = allocation.allocate(jobId).orElseThrow();
    Map<String, Object> execution = executions.find(executionId);
    String requestId = execution.get("submitRequestId").toString();
    String externalId = "synthetic-" + UUID.randomUUID();
    Path workspace = Path.of(execution.get("workspace").toString());
    Files.createDirectories(workspace);
    Files.writeString(
        workspace.resolve("solver.log"), "Synthetic test Solver SUCCESS; no commercial data\n");
    Map<String, Object> config = new LinkedHashMap<>(json.readMap(execution.get("request")));
    config.put("workspace", workspace.toString());
    config.put("inputDirectory", jobs.findById(jobId).get("workspace") + "/input");
    config.put("externalExecutionId", externalId);
    config.put("nodeId", "node-a");
    config.put("parameters", manifest.get("parameters"));
    Files.writeString(workspace.resolve("execution-config.json"), json.write(config));
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("executionRequestId", requestId);
    observation.put("externalExecutionId", externalId);
    observation.put("state", "SUCCESS");
    observation.put("progress", 100);
    observation.put("pid", 4242);
    observation.put("processIdentity", "4242:synthetic-stable-start");
    observation.put("exitCode", 0);
    observation.put("startedAt", Instant.now().minusSeconds(2).toString());
    observation.put("finishedAt", Instant.now().toString());
    Map<String, Object> sidecar = new LinkedHashMap<>(observation);
    sidecar.put("workspace", workspace.toString());
    Files.writeString(workspace.resolve("state.json"), json.write(sidecar));
    Map<String, Object> raw = raw(analysis, condition, subcase);
    Files.writeString(workspace.resolve("raw_result.json"), json.write(raw));
    state.callback("node-a", observation);
    assertThat(jobs.findById(jobId)).containsEntry("status", "POST_PROCESSING");
    assertThat(executions.find(executionId)).containsEntry("status", "SUCCESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM cae_resource_reservation WHERE execution_id=?",
                String.class,
                executionId))
        .isEqualTo("RELEASED");
    return new Fixture(jobId, number, executionId, requestId, workspace, manifest, raw);
  }

  protected static Map<String, Object> raw(String analysis, String condition, int subcase) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("schemaVersion", 1);
    raw.put("analysisType", analysis);
    raw.put("conditionCode", condition);
    raw.put("subcase", subcase);
    raw.put("solverProfile", analysis + "_STANDARD");
    raw.put("seed", 37);
    raw.put("parameters", Map.of("seed", 37, "thicknessScale", 1, "subcase", subcase));
    switch (analysis) {
      case "STRUCTURE" -> {
        raw.put("mass_kg", new BigDecimal("315.125"));
        raw.put("torsional_stiffness_nm_per_deg", new BigDecimal("21111.250"));
        raw.put("bending_stiffness_n_per_mm", new BigDecimal("18666.750"));
        raw.put("modes_hz", List.of(28.6, 31.9, 42.3, 49.8));
      }
      case "NVH" -> {
        raw.put("peak_frequency_hz", new BigDecimal("85.125"));
        raw.put("peak_response_db", new BigDecimal("62.250"));
      }
      case "CRASH" -> {
        raw.put("max_intrusion_mm", new BigDecimal("140.125"));
        raw.put("peak_acceleration_g", new BigDecimal("35.250"));
        raw.put("energy_balance_ratio", new BigDecimal("0.9825"));
      }
      case "CFD" -> {
        raw.put("cd", new BigDecimal("0.27812"));
        raw.put("cl", new BigDecimal("-0.04512"));
        raw.put("residual_final", new BigDecimal("0.000001"));
        raw.put("converged", true);
      }
      default -> throw new IllegalArgumentException("Unknown test analysis");
    }
    return raw;
  }

  protected int postCommands(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM cae_outbox_event WHERE aggregate_id=? "
            + "AND event_type='POST_PROCESS_REQUESTED' AND exchange_name='cae.command'",
        Integer.class,
        fixture.jobId());
  }

  protected void rewrite(Fixture fixture, String file, String field, Object value)
      throws IOException {
    Path path = fixture.workspace().resolve(file);
    Map<String, Object> document = new LinkedHashMap<>(json.readMap(Files.readString(path)));
    document.put(field, value);
    Files.writeString(path, json.write(document));
  }

  protected static String sha(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var stream = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  protected record Fixture(
      long jobId,
      String jobNo,
      long executionId,
      String requestId,
      Path workspace,
      Map<String, Object> manifest,
      Map<String, Object> raw) {}

  private static Path temporaryRoot() {
    try {
      return Files.createTempDirectory("cae-results-it-");
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void removeOwned(Path path, Path allowedRoot) throws IOException {
    if (!path.toAbsolutePath().normalize().startsWith(allowedRoot.toAbsolutePath().normalize()))
      throw new IllegalStateException("Test cleanup escaped its owned root");
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
    try (var paths = Files.walk(path)) {
      for (Path child : paths.sorted(java.util.Comparator.reverseOrder()).toList())
        Files.deleteIfExists(child);
    }
  }
}
