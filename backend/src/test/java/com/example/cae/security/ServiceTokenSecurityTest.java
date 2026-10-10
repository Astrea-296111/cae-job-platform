package com.example.cae.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.common.JsonCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "SERVICE_TOKEN=security-test-token")
@AutoConfigureMockMvc
class ServiceTokenSecurityTest {
  private static final String TOKEN = "security-test-token";
  private static final String HEARTBEAT = "/internal/v1/agents/node-a/heartbeat";
  @Autowired MockMvc mvc;
  @Autowired JsonCodec json;

  @Test
  void internalHeartbeatRequiresServiceTokenEvenForAuthenticatedAdmin() throws Exception {
    mvc.perform(get("/internal")).andExpect(status().isUnauthorized());
    mvc.perform(post(HEARTBEAT).contentType("application/json").content(json.write(payload())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.traceId").isString());
    mvc.perform(
            post(HEARTBEAT)
                .with(httpBasic("admin", "admin123"))
                .contentType("application/json")
                .content(json.write(payload())))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(HEARTBEAT)
                .header("X-Service-Token", TOKEN + "-wrong")
                .contentType("application/json")
                .content(json.write(payload())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validServiceTokenAllowsAgentHeartbeatWithoutHumanBasicAuthentication() throws Exception {
    mvc.perform(
            post(HEARTBEAT)
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(json.write(payload())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.nodeId").value("node-a"));
    mvc.perform(get(HEARTBEAT).header("X-Service-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.cpuTotal").value(16));
    mvc.perform(get(HEARTBEAT)).andExpect(status().isUnauthorized());
  }

  @Test
  void serviceTokenDoesNotGrantHumanProjectPermissionsAndHealthRemainsPublic() throws Exception {
    mvc.perform(get("/api/v1/projects").header("X-Service-Token", TOKEN))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void heartbeatCannotSpoofAnotherNodeOrClaimUnconfiguredNode() throws Exception {
    Map<String, Object> wrong = payload();
    wrong.put("nodeId", "node-b");
    mvc.perform(
            post(HEARTBEAT)
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(json.write(wrong)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    wrong.put("nodeId", "unconfigured-node");
    mvc.perform(
            post("/internal/v1/agents/unconfigured-node/heartbeat")
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(json.write(wrong)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void heartbeatUsageCannotExceedCpuOrMemoryCapacityIncludingIntegerOverflow() throws Exception {
    for (String field : List.of("cpuUsed", "memoryUsedGb")) {
      Map<String, Object> excessive = payload();
      excessive.put(field, 1000);
      mvc.perform(
              post(HEARTBEAT)
                  .header("X-Service-Token", TOKEN)
                  .contentType("application/json")
                  .content(json.write(excessive)))
          .andExpect(status().isBadRequest());
    }
    for (Map<String, Object> overflow :
        List.of(
            Map.<String, Object>of("cpuUsed", Integer.MAX_VALUE, "cpuReserved", Integer.MAX_VALUE),
            Map.<String, Object>of(
                "memoryUsedGb", Integer.MAX_VALUE, "memoryReservedGb", Integer.MAX_VALUE))) {
      Map<String, Object> excessive = payload();
      excessive.putAll(overflow);
      mvc.perform(
              post(HEARTBEAT)
                  .header("X-Service-Token", TOKEN)
                  .contentType("application/json")
                  .content(json.write(excessive)))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void heartbeatRequiresOnlineAndRuntimeListsAndRejectsNegativeReservations() throws Exception {
    for (String field : List.of("online", "solverTags", "runningJobs")) {
      Map<String, Object> incomplete = payload();
      incomplete.remove(field);
      mvc.perform(
              post(HEARTBEAT)
                  .header("X-Service-Token", TOKEN)
                  .contentType("application/json")
                  .content(json.write(incomplete)))
          .andExpect(status().isBadRequest());
    }
    Map<String, Object> invalid = payload();
    invalid.put("cpuReserved", -1);
    mvc.perform(
            post(HEARTBEAT)
                .header("X-Service-Token", TOKEN)
                .contentType("application/json")
                .content(json.write(invalid)))
        .andExpect(status().isBadRequest());
  }

  private Map<String, Object> payload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nodeId", "node-a");
    payload.put("hostname", "synthetic-node-a");
    payload.put("online", true);
    payload.put("cpuTotal", 16);
    payload.put("cpuUsed", 4);
    payload.put("cpuReserved", 4);
    payload.put("memoryTotalGb", 32);
    payload.put("memoryUsedGb", 8);
    payload.put("memoryReservedGb", 8);
    payload.put("solverTags", List.of("STRUCTURE", "NVH"));
    payload.put("runningJobs", List.of("CAE-20261010-000007"));
    return payload;
  }
}
