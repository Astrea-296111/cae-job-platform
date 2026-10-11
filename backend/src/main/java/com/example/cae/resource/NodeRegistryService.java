package com.example.cae.resource;

import com.example.cae.agent.AgentHeartbeatController;
import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NodeRegistryService {
  private static final Logger LOG = LoggerFactory.getLogger(NodeRegistryService.class);
  private final JdbcTemplate jdbc;
  private final JsonCodec json;
  private final StringRedisTemplate redis;
  private final ComputeAgentClient agents;
  private final ProjectAccess access;
  private final JobRepository jobs;
  private final long suspectSeconds;
  private final long offlineSeconds;
  private final boolean backgroundEnabled;
  private final String cacheNamespace;

  public NodeRegistryService(
      JdbcTemplate jdbc,
      JsonCodec json,
      StringRedisTemplate redis,
      ComputeAgentClient agents,
      ProjectAccess access,
      JobRepository jobs,
      @Value("${cae.nodes.suspect-seconds:10}") long suspectSeconds,
      @Value("${cae.nodes.offline-seconds:20}") long offlineSeconds,
      @Value("${cae.background.enabled:true}") boolean backgroundEnabled,
      @Value("${CAE_CACHE_NAMESPACE:cae}") String cacheNamespace) {
    this.jdbc = jdbc;
    this.json = json;
    this.redis = redis;
    this.agents = agents;
    this.access = access;
    this.jobs = jobs;
    this.suspectSeconds = suspectSeconds;
    this.offlineSeconds = offlineSeconds;
    this.backgroundEnabled = backgroundEnabled;
    if (!cacheNamespace.matches("[A-Za-z0-9_-]{1,80}"))
      throw new IllegalArgumentException("Cache namespace must be a bounded identifier");
    this.cacheNamespace = cacheNamespace;
    if (suspectSeconds < 1 || offlineSeconds <= suspectSeconds)
      throw new IllegalArgumentException("Heartbeat timeouts must be ordered positive values");
  }

  @PostConstruct
  void configuredUrls() {
    // Only server-configured origins may register; a newly configured node starts OFFLINE.
    agents
        .nodes()
        .forEach(
            (id, url) ->
                jdbc.update(
                    "INSERT INTO cae_compute_node(node_id,hostname,agent_url,cpu_total,memory_total_gb,solver_tags,running_jobs) VALUES(?,?,?,1,1,JSON_ARRAY(),JSON_ARRAY()) ON DUPLICATE KEY UPDATE agent_url=VALUES(agent_url)",
                    id,
                    id,
                    url.toString()));
  }

  @EventListener
  @Transactional
  public void heartbeat(AgentHeartbeatController.Observation observation) {
    if (!agents.nodes().containsKey(observation.nodeId()))
      throw ApiException.invalid("Heartbeat node must be configured");
    var p = observation.payload();
    int changed =
        jdbc.update(
            "UPDATE cae_compute_node SET hostname=?,status=?,cpu_total=?,observed_cpu_used=?,memory_total_gb=?,observed_memory_used_gb=?,solver_tags=?,running_jobs=?,workspace_reachable=?,last_heartbeat=?,updated_at=CURRENT_TIMESTAMP(6) WHERE node_id=? AND (last_heartbeat IS NULL OR last_heartbeat<=?)",
            p.hostname(),
            Boolean.TRUE.equals(p.online()) ? "ONLINE" : "OFFLINE",
            p.cpuTotal(),
            p.cpuUsed(),
            p.memoryTotalGb(),
            p.memoryUsedGb(),
            json.write(p.solverTags()),
            json.write(p.runningJobs()),
            Boolean.TRUE.equals(p.workspaceReachable()),
            Timestamp.from(observation.receivedAt()),
            observation.nodeId(),
            Timestamp.from(observation.receivedAt()));
    if (changed > 0) cacheAfterCommit(observation.nodeId());
  }

  @Scheduled(fixedDelayString = "${cae.nodes.refresh-ms:5000}")
  public void refreshOnlineStates() {
    if (!backgroundEnabled) return;
    jdbc.update(
        "UPDATE cae_compute_node SET status=CASE WHEN last_heartbeat IS NULL OR last_heartbeat<TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) THEN 'OFFLINE' WHEN last_heartbeat<TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) THEN 'SUSPECT' ELSE status END WHERE status<>'OFFLINE'",
        -offlineSeconds,
        -suspectSeconds);
    // Expiry observations refresh the UI cache; active reservations remain untouched.
    for (Map<String, Object> node : nodes()) cache(node.get("nodeId").toString());
  }

  /**
   * Scheduler always reads durable rows. Cached observations are for an individual UI node card.
   */
  public List<Map<String, Object>> nodes() {
    List<Map<String, Object>> rows =
        jdbc.query("SELECT * FROM cae_compute_node ORDER BY node_id", SqlRows.MAPPER);
    rows.forEach(this::decode);
    return rows;
  }

  public Map<String, Object> find(String nodeId) {
    List<Map<String, Object>> rows =
        jdbc.query("SELECT * FROM cae_compute_node WHERE node_id=?", SqlRows.MAPPER, nodeId);
    if (rows.isEmpty()) throw ApiException.notFound("Compute node");
    return decode(rows.get(0));
  }

  public Map<String, Object> cached(String nodeId) {
    // Verify existence in MySQL even if a stale/deleted cache entry remains.
    Map<String, Object> durable = find(nodeId);
    try {
      String encoded = redis.opsForValue().get(key(nodeId));
      if (encoded != null) {
        Map<String, Object> snapshot = json.readMap(encoded);
        // Ledger and admission flags remain current; Redis supplies just the observation snapshot.
        for (String field :
            List.of(
                "hostname",
                "observedCpuUsed",
                "observedMemoryUsedGb",
                "runningJobs",
                "lastHeartbeat")) durable.put(field, snapshot.get(field));
      } else
        redis
            .opsForValue()
            .set(key(nodeId), json.write(durable), Duration.ofSeconds(offlineSeconds + 5));
    } catch (DataAccessException e) {
      unavailable(nodeId);
    }
    return durable;
  }

  @Transactional
  public Map<String, Object> drain(Authentication user, String nodeId, boolean drained) {
    if (!access.admin(user)) throw ApiException.forbidden();
    Map<String, Object> before = find(nodeId);
    jdbc.update(
        "UPDATE cae_compute_node SET drained=?,updated_at=CURRENT_TIMESTAMP(6) WHERE node_id=?",
        drained,
        nodeId);
    jobs.audit(
        user.getName(),
        "NODE_DRAIN",
        "COMPUTE_NODE",
        nodeId,
        Map.of("drained", before.get("drained")),
        Map.of("drained", drained));
    cacheAfterCommit(nodeId);
    return find(nodeId);
  }

  private Map<String, Object> decode(Map<String, Object> row) {
    for (String field : List.of("solverTags", "runningJobs"))
      row.put(field, json.readMap("{\"items\":" + row.get(field) + "}").get("items"));
    row.put("online", "ONLINE".equals(row.get("status")));
    return row;
  }

  private String key(String nodeId) {
    return cacheNamespace + ":node:" + nodeId;
  }

  private void cacheAfterCommit(String nodeId) {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              cache(nodeId);
            }
          });
    else cache(nodeId);
  }

  private void cache(String nodeId) {
    try {
      redis
          .opsForValue()
          .set(key(nodeId), json.write(find(nodeId)), Duration.ofSeconds(offlineSeconds + 5));
    } catch (DataAccessException e) {
      unavailable(nodeId);
    }
  }

  private void unavailable(String nodeId) {
    LOG.warn(
        "Redis node snapshot unavailable nodeId={}; durable MySQL observation remains available",
        nodeId);
  }
}
