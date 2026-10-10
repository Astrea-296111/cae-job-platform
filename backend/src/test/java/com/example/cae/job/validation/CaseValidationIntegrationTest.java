package com.example.cae.job.validation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CaseValidationIntegrationTest {
  private static final Path ROOT = tempRoot();

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("cae-case-validation-it-");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void roots(DynamicPropertyRegistry registry) {
    registry.add("CASE_ROOT", () -> ROOT.resolve("cases").toString());
    registry.add("DATA_ROOT", () -> ROOT.resolve("data").toString());
  }

  @Autowired MockMvc mvc;
  @Autowired JobRepository jobs;
  @Autowired JsonCodec json;
  @Autowired InputSnapshotService snapshots;

  private void fixture(String name, boolean missing) throws IOException {
    Path source = ROOT.resolve("cases").resolve(name);
    Files.createDirectories(source);
    Files.writeString(source.resolve("model.fem"), "$ synthetic input\nINCLUDE 'materials.inc'\n");
    if (!missing) Files.writeString(source.resolve("materials.inc"), "MAT1 SYNTHETIC\n");
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
  }

  private String create(String casePath) throws Exception {
    Map<String, Object> created =
        json.readMap(
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
                                    casePath))))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return created.get("jobNo").toString();
  }

  @Test
  void validationCreatesReadySnapshotAndCompleteEvents() throws Exception {
    fixture("valid", false);
    String jobNo = create("valid");
    mvc.perform(
            post("/api/v1/jobs/" + jobNo + "/validate").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.inputChecksum").isString());
    Map<String, Object> job = jobs.findByNo(jobNo);
    String original =
        Files.readString(Path.of(job.get("workspace").toString()).resolve("input/materials.inc"));
    Files.writeString(ROOT.resolve("cases/valid/materials.inc"), "changed original\n");
    snapshots.verifySnapshot(job);
    assertThat(
            Files.readString(
                Path.of(job.get("workspace").toString()).resolve("input/materials.inc")))
        .isEqualTo(original);
    assertThat(jobs.events(SqlRows.id(job)).stream().map(e -> e.get("eventType")))
        .containsExactly("JOB_CREATED", "VALIDATION_STARTED", "JOB_READY");
    int count = jobs.events(SqlRows.id(job)).size();
    mvc.perform(
            post("/api/v1/jobs/" + jobNo + "/validate").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"));
    assertThat(jobs.events(SqlRows.id(job))).hasSize(count);
  }

  @Test
  void missingIncludePersistsFailureAndCanBeRetriedAfterRepair() throws Exception {
    fixture("missing", true);
    String jobNo = create("missing");
    mvc.perform(
            post("/api/v1/jobs/" + jobNo + "/validate").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.failureCode").value("INPUT_INCLUDE_MISSING"));
    Map<String, Object> failed = jobs.findByNo(jobNo);
    assertThat(failed.get("workspace")).isNull();
    Map<String, Object> failure = jobs.events(SqlRows.id(failed)).get(2);
    assertThat(json.readMap(failure.get("detail")).get("retryable")).isEqualTo(false);
    Files.writeString(ROOT.resolve("cases/missing/materials.inc"), "MAT1 repaired\n");
    mvc.perform(
            post("/api/v1/jobs/" + jobNo + "/validate").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.failureCode").doesNotExist());
  }

  @Test
  void unauthorizedProjectCannotValidateJob() throws Exception {
    fixture("auth", false);
    String jobNo = create("auth");
    mvc.perform(post("/api/v1/jobs/" + jobNo + "/validate")).andExpect(status().isUnauthorized());
    assertThat(jobs.findByNo(jobNo).get("status")).isEqualTo("CREATED");
  }

  @Test
  void mismatchedModelRevisionCannotBecomeReady() throws Exception {
    fixture("wrong-revision", false);
    Path path = ROOT.resolve("cases/wrong-revision/manifest.json");
    Map<String, Object> manifest =
        new java.util.LinkedHashMap<>(json.readMap(Files.readString(path)));
    manifest.put("modelRevision", "V36");
    Files.writeString(path, json.write(manifest));
    String jobNo = create("wrong-revision");
    mvc.perform(
            post("/api/v1/jobs/" + jobNo + "/validate").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.failureCode").value("INPUT_INVALID"));
    assertThat(jobs.findByNo(jobNo).get("workspace")).isNull();
  }
}
