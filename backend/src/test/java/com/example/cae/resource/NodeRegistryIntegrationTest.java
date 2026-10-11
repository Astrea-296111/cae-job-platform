package com.example.cae.resource;

import static org.assertj.core.api.Assertions.*;

import com.example.cae.agent.AgentHeartbeatController;
import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.JsonCodec;
import com.example.cae.job.repository.JobRepository;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class NodeRegistryIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonCodec json;
  @Autowired StringRedisTemplate redis;
  @Autowired ProjectAccess access;
  @Autowired JobRepository jobs;
  @Autowired TransactionTemplate transactions;
  private String nodeId;
  private NodeRegistryService registry;
  private ComputeAgentClient agents;
  private final String namespace = System.getenv().getOrDefault("CAE_CACHE_NAMESPACE", "cae");

  @BeforeEach
  void register() {
    nodeId = "registry-" + UUID.randomUUID().toString().replace("-", "");
    agents =
        new ComputeAgentClient(
            Map.of(nodeId, URI.create("http://127.0.0.1:9")),
            "test-token",
            Duration.ofMillis(50),
            json);
    registry =
        new NodeRegistryService(jdbc, json, redis, agents, access, jobs, 10, 20, false, namespace);
    registry.configuredUrls();
  }

  @AfterEach
  void cleanup() {
    jdbc.update(
        "UPDATE cae_compute_node SET status='OFFLINE',cpu_reserved=0,cpu_used=0,memory_reserved_gb=0,memory_used_gb=0 WHERE node_id=?",
        nodeId);
    redis.delete(namespace + ":node:" + nodeId);
  }

  private AgentHeartbeatController.Observation observation(Instant at, int observedCpu) {
    return new AgentHeartbeatController.Observation(
        nodeId,
        at,
        new AgentHeartbeatController.Payload(
            nodeId,
            "synthetic-host",
            true,
            true,
            16,
            observedCpu,
            0,
            32,
            observedCpu * 2,
            0,
            List.of("STRUCTURE"),
            List.of("exec-synthetic")));
  }

  @Test
  void
      heartbeatUpdatesDurableObservationsWithoutOverwritingTheResourceLedgerAndCachesAfterCommit() {
    jdbc.update(
        "UPDATE cae_compute_node SET cpu_total=16,memory_total_gb=32,cpu_reserved=4,cpu_used=4,memory_reserved_gb=8,memory_used_gb=8 WHERE node_id=?",
        nodeId);
    transactions.executeWithoutResult(tx -> registry.heartbeat(observation(Instant.now(), 8)));
    Map<String, Object> durable = registry.find(nodeId);
    assertThat(durable)
        .containsEntry("status", "ONLINE")
        .containsEntry("cpuReserved", 4)
        .containsEntry("cpuUsed", 4)
        .containsEntry("observedCpuUsed", 8)
        .containsEntry("memoryReservedGb", 8)
        .containsEntry("memoryUsedGb", 8)
        .containsEntry("observedMemoryUsedGb", 16);
    String encoded = redis.opsForValue().get(namespace + ":node:" + nodeId);
    assertThat(encoded).isNotNull();
    assertThat(json.readMap(encoded)).containsEntry("nodeId", nodeId);
    assertThat(redis.getExpire(namespace + ":node:" + nodeId)).isBetween(1L, 25L);
    assertThat(registry.cached(nodeId).get("runningJobs")).isEqualTo(List.of("exec-synthetic"));
  }

  @Test
  void rolledBackHeartbeatCannotPublishAnObservationToRedis() {
    transactions.executeWithoutResult(
        tx -> {
          registry.heartbeat(observation(Instant.now(), 4));
          tx.setRollbackOnly();
        });
    assertThat(registry.find(nodeId).get("lastHeartbeat")).isNull();
    assertThat(redis.opsForValue().get(namespace + ":node:" + nodeId)).isNull();
  }

  @Test
  void aLateOlderHeartbeatCannotReplaceANewerObservation() {
    Instant now = Instant.now();
    transactions.executeWithoutResult(tx -> registry.heartbeat(observation(now, 8)));
    transactions.executeWithoutResult(
        tx -> registry.heartbeat(observation(now.minusSeconds(60), 0)));
    assertThat(registry.find(nodeId).get("observedCpuUsed")).isEqualTo(8);
  }

  @Test
  void expiryProgressesThroughSuspectAndOfflineWithoutReleasingAnyReservations() {
    NodeRegistryService enabled =
        new NodeRegistryService(jdbc, json, redis, agents, access, jobs, 10, 20, true, namespace);
    jdbc.update(
        "UPDATE cae_compute_node SET cpu_total=16,memory_total_gb=32,status='ONLINE',cpu_reserved=4,memory_reserved_gb=8,last_heartbeat=? WHERE node_id=?",
        Timestamp.from(Instant.now().minusSeconds(15)),
        nodeId);
    registry.refreshOnlineStates();
    assertThat(registry.find(nodeId).get("status")).isEqualTo("ONLINE");
    enabled.refreshOnlineStates();
    assertThat(registry.find(nodeId).get("status")).isEqualTo("SUSPECT");
    jdbc.update(
        "UPDATE cae_compute_node SET last_heartbeat=? WHERE node_id=?",
        Timestamp.from(Instant.now().minusSeconds(25)),
        nodeId);
    enabled.refreshOnlineStates();
    assertThat(registry.find(nodeId))
        .containsEntry("status", "OFFLINE")
        .containsEntry("cpuReserved", 4)
        .containsEntry("memoryReservedGb", 8);
    transactions.executeWithoutResult(tx -> registry.heartbeat(observation(Instant.now(), 0)));
    assertThat(registry.find(nodeId).get("status")).isEqualTo("ONLINE");
  }
}
