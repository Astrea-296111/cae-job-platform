package com.example.cae.job;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.repository.JobRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class JobDomainIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JobRepository jobs;
  @Autowired JsonCodec json;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;

  private Map<String, Object> request() {
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
        "resource",
        Map.of("cpu", 8, "memoryGb", 16, "walltimeMin", 30));
  }

  private Map<String, Object> create() throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/jobs")
                    .with(httpBasic("engineer", "engineer123"))
                    .contentType("application/json")
                    .content(json.write(request())))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return jobs.findByNo(json.readMap(body).get("jobNo").toString());
  }

  @Test
  void createReadFilterAndEventsUseRealMysql() throws Exception {
    Map<String, Object> job = create();
    String number = job.get("jobNo").toString();
    assertThat(number).matches("CAE-\\d{8}-\\d{6,}");
    mvc.perform(get("/api/v1/jobs/" + number).with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submitterId").value("engineer"))
        .andExpect(jsonPath("$.resource.cpu").value(8));
    mvc.perform(
            get("/api/v1/jobs")
                .param("status", "CREATED")
                .param("projectId", "1")
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.total").isNumber());
    mvc.perform(
            get("/api/v1/jobs/" + number + "/events").with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].eventType").value("JOB_CREATED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_audit_log WHERE operation='JOB_CREATE' AND target_id=?",
                Integer.class,
                number))
        .isEqualTo(1);
  }

  @Test
  void casRejectsStaleSnapshotAndDuplicateTransitionDoesNotAppendEvents() throws Exception {
    Map<String, Object> job = create();
    long id = SqlRows.id(job);
    Map<String, Object> validating =
        jobs.transition(job, JobStatus.VALIDATING, "VALIDATION_STARTED", Map.of());
    assertThat(validating.get("version")).isEqualTo(1L);
    assertThatThrownBy(
            () -> jobs.transition(job, JobStatus.CANCEL_REQUESTED, "CANCEL_REQUESTED", Map.of()))
        .isInstanceOf(ApiException.class);
    int before = jobs.events(id).size();
    jobs.transition(id, JobStatus.VALIDATING, "VALIDATION_STARTED", Map.of());
    assertThat(jobs.events(id)).hasSize(before);
    assertThatThrownBy(() -> jobs.patch(id, Map.of("status", "RUNNING")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transactionRollbackUndoesStatusAndEventTogether() throws Exception {
    Map<String, Object> job = create();
    long id = SqlRows.id(job);
    int events = jobs.events(id).size();
    transactions.executeWithoutResult(
        tx -> {
          jobs.transition(id, JobStatus.VALIDATING, "VALIDATION_STARTED", Map.of());
          tx.setRollbackOnly();
        });
    assertThat(jobs.findById(id).get("status")).isEqualTo("CREATED");
    assertThat(jobs.events(id)).hasSize(events);
  }

  @Test
  void staleMetadataCannotReplaceNewExecutionAssociation() throws Exception {
    Map<String, Object> job = create();
    Map<String, Object> changed = jobs.patch(job, Map.of("currentExecutionId", 101L));
    assertThat(changed.get("currentExecutionId")).isEqualTo(101L);
    assertThatThrownBy(() -> jobs.patch(job, Map.of("currentExecutionId", 102L)))
        .isInstanceOf(ApiException.class);
    assertThat(jobs.findById(SqlRows.id(job)).get("currentExecutionId")).isEqualTo(101L);
  }

  @Test
  void invalidQueryAndInputTypesReturnClientErrors() throws Exception {
    for (Map<String, String> query :
        List.of(
            Map.of("page", "abc"),
            Map.of("projectId", "abc"),
            Map.of("page", "2147483647", "size", "100"))) {
      var request = get("/api/v1/jobs").with(httpBasic("engineer", "engineer123"));
      query.forEach(request::param);
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    Map<String, Object> invalid = new java.util.LinkedHashMap<>(request());
    invalid.put("input", Map.of("manifest", Map.of("not", "a path")));
    mvc.perform(
            post("/api/v1/jobs")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(invalid)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void concurrentCasOnlyOneWriterSucceeds() throws Exception {
    Map<String, Object> job = create();
    var pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Boolean>> calls = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        calls.add(
            () -> {
              try {
                jobs.transition(job, JobStatus.VALIDATING, "VALIDATION_STARTED", Map.of());
                return true;
              } catch (ApiException expected) {
                return false;
              }
            });
      long winners =
          pool.invokeAll(calls).stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .count();
      assertThat(winners).isEqualTo(1);
      assertThat(jobs.events(SqlRows.id(job))).hasSize(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void projectOwnershipAndRoleBoundariesAreEnforced() throws Exception {
    String project =
        mvc.perform(
                post("/api/v1/projects")
                    .with(httpBasic("engineer", "engineer123"))
                    .contentType("application/json")
                    .content(
                        json.write(
                            Map.of("code", "P-" + UUID.randomUUID(), "name", "Private synthetic"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long projectId = SqlRows.id(json.readMap(project));
    mvc.perform(
            get("/api/v1/models")
                .param("projectId", Long.toString(projectId))
                .with(httpBasic("lead", "lead123")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    String number = create().get("jobNo").toString();
    mvc.perform(
            patch("/api/v1/jobs/" + number + "/priority")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content("{\"priority\":80}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/v1/jobs/" + number + "/priority")
                .with(httpBasic("lead", "lead123"))
                .contentType("application/json")
                .content("{\"priority\":80}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(80));
  }

  @Test
  void modelRevisionAndSolverProfileMustMatchProjectAndAnalysis() throws Exception {
    Map<String, Object> bad = new java.util.LinkedHashMap<>(request());
    bad.put("modelRevisionId", 3);
    mvc.perform(
            post("/api/v1/jobs")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(bad)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.traceId").isString());
    bad = new java.util.LinkedHashMap<>(request());
    bad.put("solverProfile", "CRASH_STANDARD");
    mvc.perform(
            post("/api/v1/jobs")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(bad)))
        .andExpect(status().isBadRequest());
    bad = new java.util.LinkedHashMap<>(request());
    bad.put("resource", Map.of("cpu", 512));
    mvc.perform(
            post("/api/v1/jobs")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(bad)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void projectModelAndRevisionCrudPersistRelationships() throws Exception {
    String suffix = UUID.randomUUID().toString();
    Map<String, Object> project =
        json.readMap(
            mvc.perform(
                    post("/api/v1/projects")
                        .with(httpBasic("engineer", "engineer123"))
                        .contentType("application/json")
                        .content(
                            json.write(Map.of("code", "CRUD-" + suffix, "name", "CRUD synthetic"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    Map<String, Object> model =
        json.readMap(
            mvc.perform(
                    post("/api/v1/models")
                        .with(httpBasic("engineer", "engineer123"))
                        .contentType("application/json")
                        .content(
                            json.write(
                                Map.of(
                                    "projectId",
                                    project.get("id"),
                                    "code",
                                    "MODEL",
                                    "name",
                                    "Synthetic model"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    mvc.perform(
            post("/api/v1/model-revisions")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(Map.of("modelId", model.get("id"), "revision", "V1"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.revision").value("V1"));
    mvc.perform(
            get("/api/v1/model-revisions")
                .param("projectId", project.get("id").toString())
                .with(httpBasic("engineer", "engineer123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].projectId").value(project.get("id")));
  }
}
