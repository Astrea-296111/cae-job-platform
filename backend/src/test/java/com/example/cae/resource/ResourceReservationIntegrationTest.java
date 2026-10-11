package com.example.cae.resource;

import static org.assertj.core.api.Assertions.*;

import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ResourceReservationIntegrationTest {
  @Autowired ResourceReservationService resources;
  @Autowired JobRepository jobs;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;
  private String nodeId;
  private String feature;

  @BeforeEach
  void capacity() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    nodeId = "test-" + suffix;
    feature = "T" + suffix.substring(0, 16);
    jdbc.update("INSERT INTO cae_license_pool(feature,total_tokens) VALUES(?,4)", feature);
    jdbc.update(
        "INSERT INTO cae_compute_node(node_id,hostname,agent_url,status,workspace_reachable,cpu_total,memory_total_gb,solver_tags,running_jobs,last_heartbeat) VALUES(?,?,'http://127.0.0.1:9','ONLINE',TRUE,16,32,JSON_ARRAY('STRUCTURE'),JSON_ARRAY(),CURRENT_TIMESTAMP(6))",
        nodeId,
        nodeId);
  }

  @AfterEach
  void releaseFixtures() {
    for (Long id :
        jdbc.queryForList(
            "SELECT id FROM cae_resource_reservation WHERE node_id=? AND status IN ('RESERVED','IN_USE')",
            Long.class,
            nodeId)) resources.release(id);
    jdbc.update("UPDATE cae_compute_node SET status='OFFLINE' WHERE node_id=?", nodeId);
  }

  private long job() {
    return SqlRows.id(
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
                Map.entry("licenseFeature", feature))));
  }

  private ResourceReservationService.Reservation reserve(long job) {
    return transactions.execute(
        tx -> resources.reserve(job, nodeId, 4, 8, feature, 1).orElseThrow());
  }

  @Test
  void fiftySimultaneousTransactionsAdmitExactlyFourWithoutOversellingCpuMemoryOrLicenses()
      throws Exception {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 50; i++) ids.add(job());
    var ready = new CountDownLatch(50);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(50);
    try {
      List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
      for (long id : ids)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Start barrier timed out");
                  return transactions.execute(
                      tx -> resources.reserve(id, nodeId, 4, 8, feature, 1).isPresent());
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int accepted = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) accepted++;
      assertThat(accepted).isEqualTo(4);
      assertThat(
              jdbc.queryForMap(
                  "SELECT cpu_reserved,memory_reserved_gb,cpu_used,memory_used_gb FROM cae_compute_node WHERE node_id=?",
                  nodeId))
          .containsEntry("cpu_reserved", 16)
          .containsEntry("memory_reserved_gb", 32)
          .containsEntry("cpu_used", 0)
          .containsEntry("memory_used_gb", 0);
      assertThat(
              jdbc.queryForMap(
                  "SELECT reserved_tokens,in_use_tokens FROM cae_license_pool WHERE feature=?",
                  feature))
          .containsEntry("reserved_tokens", 4)
          .containsEntry("in_use_tokens", 0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM cae_resource_reservation WHERE node_id=? AND status='RESERVED'",
                  Integer.class,
                  nodeId))
          .isEqualTo(4);
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void duplicateReservationIsIdempotentAndDifferentImmutableRequestIsRejected() {
    long id = job();
    var first = reserve(id);
    var repeated = reserve(id);
    assertThat(repeated.id()).isEqualTo(first.id());
    assertThat(
            jdbc.queryForObject(
                "SELECT cpu_reserved FROM cae_compute_node WHERE node_id=?", Integer.class, nodeId))
        .isEqualTo(4);
    assertThatThrownBy(
            () ->
                transactions.<java.util.Optional<ResourceReservationService.Reservation>>execute(
                    tx -> resources.reserve(id, nodeId, 8, 8, feature, 1)))
        .isInstanceOf(ApiException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_tokens FROM cae_license_pool WHERE feature=?",
                Integer.class,
                feature))
        .isEqualTo(1);
  }

  @Test
  void concurrentAcceptanceAndReleaseChangeEachLedgerExactlyOnce() throws Exception {
    var reservation = reserve(job());
    var pool = Executors.newFixedThreadPool(10);
    try {
      List<java.util.concurrent.Callable<Boolean>> calls = new ArrayList<>();
      for (int i = 0; i < 25; i++) calls.add(() -> resources.accepted(reservation.id()));
      int moved = 0;
      for (var result : pool.invokeAll(calls)) if (result.get()) moved++;
      assertThat(moved).isEqualTo(1);
      assertThat(
              jdbc.queryForMap(
                  "SELECT cpu_reserved,cpu_used,memory_reserved_gb,memory_used_gb FROM cae_compute_node WHERE node_id=?",
                  nodeId))
          .containsEntry("cpu_reserved", 0)
          .containsEntry("cpu_used", 4)
          .containsEntry("memory_reserved_gb", 0)
          .containsEntry("memory_used_gb", 8);
      assertThat(
              jdbc.queryForMap(
                  "SELECT reserved_tokens,in_use_tokens FROM cae_license_pool WHERE feature=?",
                  feature))
          .containsEntry("reserved_tokens", 0)
          .containsEntry("in_use_tokens", 1);
      calls.clear();
      for (int i = 0; i < 25; i++) calls.add(() -> resources.release(reservation.id()));
      int released = 0;
      for (var result : pool.invokeAll(calls)) if (result.get()) released++;
      assertThat(released).isEqualTo(1);
      assertThat(resources.accepted(reservation.id())).isFalse();
      assertZero();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void
      releasingAReservedAttemptRestoresAdmissionAndACompletedReservationDoesNotBlockTheNextAttempt() {
    long id = job();
    var first = reserve(id);
    assertThat(resources.release(first.id())).isTrue();
    assertThat(resources.release(first.id())).isFalse();
    assertZero();
    var second = reserve(id);
    assertThat(second.id()).isNotEqualTo(first.id());
  }

  @Test
  void aFailedNodeAdmissionReturnsItsLicenseReservationWithinTheSameTransaction() {
    jdbc.update("UPDATE cae_compute_node SET drained=TRUE WHERE node_id=?", nodeId);
    assertThat(
            transactions.<java.util.Optional<ResourceReservationService.Reservation>>execute(
                tx -> resources.reserve(job(), nodeId, 4, 8, feature, 1)))
        .isEmpty();
    assertZero();
  }

  @Test
  void aTransactionRollbackRemovesBothTheReservationAndTheCounterChanges() {
    long id = job();
    transactions.executeWithoutResult(
        tx -> {
          resources.reserve(id, nodeId, 4, 8, feature, 1).orElseThrow();
          tx.setRollbackOnly();
        });
    assertZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_resource_reservation WHERE job_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void licenseExhaustionCanPreventAdmissionEvenWhenTheNodeHasFreeCapacity() {
    jdbc.update("UPDATE cae_license_pool SET total_tokens=1 WHERE feature=?", feature);
    reserve(job());
    assertThat(
            transactions.<java.util.Optional<ResourceReservationService.Reservation>>execute(
                tx -> resources.reserve(job(), nodeId, 4, 8, feature, 1)))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT cpu_reserved FROM cae_compute_node WHERE node_id=?", Integer.class, nodeId))
        .isEqualTo(4);
  }

  @Test
  void aStaleHeartbeatOrAnIncompatibleSolverTagCannotPassTheAtomicAdmissionCheck() {
    long id = job();
    jdbc.update(
        "UPDATE cae_compute_node SET last_heartbeat=TIMESTAMPADD(SECOND,-15,CURRENT_TIMESTAMP(6)) WHERE node_id=?",
        nodeId);
    assertThat(
            transactions.<java.util.Optional<ResourceReservationService.Reservation>>execute(
                tx -> resources.reserve(id, nodeId, 4, 8, feature, 1)))
        .isEmpty();
    assertZero();
    jdbc.update(
        "UPDATE cae_compute_node SET last_heartbeat=CURRENT_TIMESTAMP(6),solver_tags=JSON_ARRAY('CFD') WHERE node_id=?",
        nodeId);
    assertThat(
            transactions.<java.util.Optional<ResourceReservationService.Reservation>>execute(
                tx -> resources.reserve(id, nodeId, 4, 8, feature, 1)))
        .isEmpty();
    assertZero();
  }

  private void assertZero() {
    assertThat(
            jdbc.queryForMap(
                "SELECT cpu_reserved,cpu_used,memory_reserved_gb,memory_used_gb FROM cae_compute_node WHERE node_id=?",
                nodeId))
        .containsEntry("cpu_reserved", 0)
        .containsEntry("cpu_used", 0)
        .containsEntry("memory_reserved_gb", 0)
        .containsEntry("memory_used_gb", 0);
    assertThat(
            jdbc.queryForMap(
                "SELECT reserved_tokens,in_use_tokens FROM cae_license_pool WHERE feature=?",
                feature))
        .containsEntry("reserved_tokens", 0)
        .containsEntry("in_use_tokens", 0);
  }
}
