package com.example.cae.resource;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.cae.common.JsonCodec;
import com.example.cae.job.repository.JobRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ResourceProfileIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JsonCodec json;
  @Autowired JobRepository jobs;
  @Autowired JdbcTemplate jdbc;

  private String name() {
    return "TEST_"
        + UUID.randomUUID()
            .toString()
            .replace("-", "")
            .substring(0, 12)
            .toUpperCase(java.util.Locale.ROOT);
  }

  private Map<String, Object> definition(String name) {
    return new LinkedHashMap<>(
        Map.of(
            "name",
            name,
            "cpu",
            3,
            "memoryGb",
            5,
            "walltimeMin",
            25,
            "licenseTokens",
            2,
            "allowedAnalysisTypes",
            List.of("STRUCTURE"),
            "enabled",
            true,
            "version",
            0));
  }

  private Map<String, Object> job(String profile) {
    return Map.of(
        "projectId",
        1,
        "modelRevisionId",
        2,
        "analysisType",
        "STRUCTURE",
        "conditionCode",
        "BIW_TORSION",
        "casePath",
        "structure/case-good",
        "resourceProfile",
        profile);
  }

  private void createProfile(Map<String, Object> definition) throws Exception {
    mvc.perform(
            post("/api/v1/resource-profiles")
                .with(httpBasic("admin", "admin123"))
                .contentType("application/json")
                .content(json.write(definition)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(0));
  }

  private String createJob(String profile) throws Exception {
    String result =
        mvc.perform(
                post("/api/v1/jobs")
                    .with(httpBasic("engineer", "engineer123"))
                    .contentType("application/json")
                    .content(json.write(job(profile))))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readMap(result).get("jobNo").toString();
  }

  @Test
  void adminCrudUsesVersionCasAndJobsRetainTheOriginalResourceAndLicenseSnapshot()
      throws Exception {
    String name = name();
    Map<String, Object> original = definition(name);
    createProfile(original);
    String first = createJob(name);
    Map<String, Object> edited = new LinkedHashMap<>(original);
    edited.put("cpu", 6);
    edited.put("memoryGb", 10);
    edited.put("licenseTokens", 1);
    mvc.perform(
            put("/api/v1/resource-profiles/" + name)
                .with(httpBasic("admin", "admin123"))
                .contentType("application/json")
                .content(json.write(edited)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            put("/api/v1/resource-profiles/" + name)
                .with(httpBasic("admin", "admin123"))
                .contentType("application/json")
                .content(json.write(edited)))
        .andExpect(status().isConflict());
    Map<String, Object> firstJob = jobs.findByNo(first);
    Map<String, Object> secondJob = jobs.findByNo(createJob(name));
    assertThat(firstJob.get("resource"))
        .isEqualTo(Map.of("cpu", 3, "memoryGb", 5, "walltimeMin", 25));
    assertThat(firstJob)
        .containsEntry("licenseFeature", "OPTISTRUCT")
        .containsEntry("licenseTokenCount", 2)
        .containsEntry("resourceProfileVersion", 0L);
    assertThat(secondJob)
        .containsEntry("licenseTokenCount", 1)
        .containsEntry("resourceProfileVersion", 1L);
    assertThat(secondJob.get("resource"))
        .isEqualTo(Map.of("cpu", 6, "memoryGb", 10, "walltimeMin", 25));
    mvc.perform(
            delete("/api/v1/resource-profiles/" + name)
                .param("version", "0")
                .with(httpBasic("admin", "admin123")))
        .andExpect(status().isConflict());
    mvc.perform(
            delete("/api/v1/resource-profiles/" + name)
                .param("version", "1")
                .with(httpBasic("admin", "admin123")))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/resource-profiles/" + name).with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isNotFound());
    assertThat(jobs.findByNo(first).get("licenseTokenCount")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_audit_log WHERE target_type='RESOURCE_PROFILE' AND target_id=?",
                Integer.class,
                name))
        .isEqualTo(3);
  }

  @Test
  void disabledOrAnalysisRestrictedProfilesCannotCreateJobs() throws Exception {
    String name = name();
    Map<String, Object> restricted = definition(name);
    restricted.put("allowedAnalysisTypes", List.of("CFD"));
    createProfile(restricted);
    mvc.perform(
            post("/api/v1/jobs")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(job(name))))
        .andExpect(status().isBadRequest());
    restricted.put("allowedAnalysisTypes", List.of("STRUCTURE"));
    restricted.put("enabled", false);
    mvc.perform(
            put("/api/v1/resource-profiles/" + name)
                .with(httpBasic("admin", "admin123"))
                .contentType("application/json")
                .content(json.write(restricted)))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/jobs")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(job(name))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void nonAdminsCanReadTemplatesButCannotChangeAdmissionSettings() throws Exception {
    Map<String, Object> definition = definition(name());
    mvc.perform(get("/api/v1/resource-profiles").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].cpu").isNumber());
    mvc.perform(
            post("/api/v1/resource-profiles")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(definition)))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/v1/resource-profiles/STANDARD")
                .param("version", "0")
                .with(httpBasic("lead", "lead123")))
        .andExpect(status().isForbidden());
  }

  @Test
  void templateBoundsAndNamesAreValidatedAtTheApiBoundary() throws Exception {
    Map<String, Object> invalid = definition(name());
    invalid.put("cpu", 0);
    invalid.put("licenseTokens", 0);
    mvc.perform(
            post("/api/v1/resource-profiles")
                .with(httpBasic("admin", "admin123"))
                .contentType("application/json")
                .content(json.write(invalid)))
        .andExpect(status().isBadRequest());
    invalid = definition("../TRAVERSAL");
    mvc.perform(
            post("/api/v1/resource-profiles")
                .with(httpBasic("admin", "admin123"))
                .contentType("application/json")
                .content(json.write(invalid)))
        .andExpect(status().isBadRequest());
  }
}
