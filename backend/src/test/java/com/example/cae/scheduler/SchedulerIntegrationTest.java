package com.example.cae.scheduler;

import static org.assertj.core.api.Assertions.*;

import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.resource.ResourceReservationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class SchedulerIntegrationTest {
  @Autowired SchedulerService scheduler;
  @Autowired ResourceReservationService resources;
  @Autowired JobRepository jobs;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;

  @Test
  void mysqlJobTimestampsSupportSortingThreeEligibleNodesAndChangingCapacityChangesTheSelection() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String feature = "S" + suffix.substring(0, 16);
    String prefix = "score-" + suffix;
    List<String> selected = new ArrayList<>();
    List<Long> reservationIds = new ArrayList<>();
    // Prevent unrelated fixtures in the isolated test schema from affecting the candidate set.
    List<Map<String, Object>> prior =
        jdbc.queryForList("SELECT node_id,drained FROM cae_compute_node");
    jdbc.update("UPDATE cae_compute_node SET drained=TRUE");
    try {
      jdbc.update("INSERT INTO cae_license_pool(feature,total_tokens) VALUES(?,4)", feature);
      for (String name : List.of("a", "b", "c")) {
        int observed =
            switch (name) {
              case "a" -> 8;
              case "b" -> 0;
              default -> 4;
            };
        jdbc.update(
            "INSERT INTO cae_compute_node(node_id,hostname,agent_url,status,workspace_reachable,cpu_total,memory_total_gb,observed_cpu_used,observed_memory_used_gb,solver_tags,running_jobs,last_heartbeat) VALUES(?,?,'http://127.0.0.1:9','ONLINE',TRUE,16,32,?,?,JSON_ARRAY('STRUCTURE'),JSON_ARRAY(),CURRENT_TIMESTAMP(6))",
            prefix + "-" + name,
            name,
            observed,
            observed * 2);
      }
      for (int i = 0; i < 5; i++) {
        Map<String, Object> job =
            jobs.create(
                Map.ofEntries(
                    Map.entry("projectId", 1),
                    Map.entry("modelRevisionId", 2),
                    Map.entry("analysisType", "STRUCTURE"),
                    Map.entry("conditionCode", "BIW_TORSION"),
                    Map.entry("solverType", "MOCK_OPTISTRUCT"),
                    Map.entry("solverProfile", "STRUCTURE_STANDARD"),
                    Map.entry("priority", 50),
                    Map.entry("submitterId", "engineer"),
                    Map.entry("resourceProfileId", "SMALL"),
                    Map.entry("resource", Map.of("cpu", 4, "memoryGb", 8, "walltimeMin", 30)),
                    Map.entry("licenseFeature", feature),
                    Map.entry("licenseTokenCount", 1)));
        assertThat(job.get("createdAt")).isInstanceOf(String.class);
        assertThat(job.get("createdAt").toString()).endsWith("Z");
        assertThat(Instant.parse(job.get("createdAt").toString())).isBeforeOrEqualTo(Instant.now());
        Optional<ResourceReservationService.Reservation> allocated =
            transactions.execute(tx -> scheduler.allocate(jobs.lockById(SqlRows.id(job))));
        if (i < 4) {
          assertThat(allocated).isPresent();
          ResourceReservationService.Reservation reservation = allocated.orElseThrow();
          selected.add(reservation.nodeId());
          reservationIds.add(reservation.id());
        } else assertThat(allocated).isEmpty();
      }
      // Free CPU and memory ratios first favor b; equal scores resolve by stable node ID.
      assertThat(selected)
          .containsExactly(prefix + "-b", prefix + "-b", prefix + "-c", prefix + "-a");
      assertThat(
              jdbc.queryForObject(
                  "SELECT cpu_reserved FROM cae_compute_node WHERE node_id=?",
                  Integer.class,
                  prefix + "-b"))
          .isEqualTo(8);
      assertThat(
              jdbc.queryForObject(
                  "SELECT SUM(cpu_reserved) FROM cae_compute_node WHERE node_id LIKE ?",
                  Integer.class,
                  prefix + "-%"))
          .isEqualTo(16);
      assertThat(
              jdbc.queryForObject(
                  "SELECT SUM(memory_reserved_gb) FROM cae_compute_node WHERE node_id LIKE ?",
                  Integer.class,
                  prefix + "-%"))
          .isEqualTo(32);
      assertThat(
              jdbc.queryForObject(
                  "SELECT reserved_tokens FROM cae_license_pool WHERE feature=?",
                  Integer.class,
                  feature))
          .isEqualTo(4);
    } finally {
      for (long id : reservationIds) resources.release(id);
      jdbc.update(
          "UPDATE cae_compute_node SET status='OFFLINE' WHERE node_id LIKE ?", prefix + "-%");
      for (Map<String, Object> node : prior)
        jdbc.update(
            "UPDATE cae_compute_node SET drained=? WHERE node_id=?",
            node.get("drained"),
            node.get("node_id"));
    }
  }
}
