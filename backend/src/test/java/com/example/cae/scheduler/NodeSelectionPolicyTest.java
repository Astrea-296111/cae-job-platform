package com.example.cae.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NodeSelectionPolicyTest {
  private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");
  private final NodeSelectionPolicy policy = policy(1, 1, 1, 1, 1);

  @ParameterizedTest
  @ValueSource(strings = {"SUSPECT", "OFFLINE"})
  void onlyOnlineNodesCanReceiveWork(String status) {
    Map<String, Object> node = node();
    assertTrue(policy.eligible(node, job()));
    node.put("status", status);
    assertFalse(policy.eligible(node, job()));
  }

  @ParameterizedTest
  @CsvSource({"drained, true", "workspaceReachable, false"})
  void administrativeDrainAndInaccessibleWorkspacePreventAdmission(String field, boolean value) {
    Map<String, Object> node = node();
    node.put(field, value);
    assertFalse(policy.eligible(node, job()));
  }

  @Test
  void solverTagsMustIncludeTheRequestedAnalysisType() {
    Map<String, Object> node = node();
    node.put("solverTags", List.of("NVH", "CFD"));
    assertFalse(policy.eligible(node, job()));
    node.put("solverTags", List.of("NVH", "STRUCTURE"));
    assertTrue(policy.eligible(node, job()));
  }

  @ParameterizedTest
  @CsvSource({
    "cpuUsed, 13",
    "observedCpuUsed, 13",
    "cpuReserved, 13",
    "memoryUsedGb, 25",
    "observedMemoryUsedGb, 25",
    "memoryReservedGb, 25"
  })
  void localUsageAgentUsageAndReservationsEachConsumeAdmissibleCapacity(String field, int usage) {
    Map<String, Object> node = node();
    node.put(field, usage);
    assertFalse(policy.eligible(node, job()), field + " must prevent resource oversubscription");
  }

  @Test
  void exactRemainingCpuAndMemoryAreEnoughButOneMoreUnitIsNot() {
    Map<String, Object> node = node();
    node.put("cpuUsed", 5);
    node.put("observedCpuUsed", 8);
    node.put("cpuReserved", 4);
    node.put("memoryUsedGb", 16);
    node.put("observedMemoryUsedGb", 12);
    node.put("memoryReservedGb", 8);

    assertTrue(policy.eligible(node, job()));
    Map<String, Object> largerJob = job();
    largerJob.put("resource", Map.of("cpu", 5, "memoryGb", 8));
    assertFalse(policy.eligible(node, largerJob));
    largerJob.put("resource", Map.of("cpu", 4, "memoryGb", 9));
    assertFalse(policy.eligible(node, largerJob));
  }

  @Test
  void localAndAgentUsageAreOverlappingObservationsRatherThanIndependentAllocations() {
    Map<String, Object> node = node();
    node.put("cpuUsed", 5);
    node.put("observedCpuUsed", 5);
    node.put("cpuReserved", 7);
    node.put("memoryUsedGb", 12);
    node.put("observedMemoryUsedGb", 12);
    node.put("memoryReservedGb", 12);

    assertTrue(
        policy.eligible(node, job()),
        "a heartbeat describing already-accounted usage must not count the same allocation twice");
  }

  @Test
  void sqlBooleanFlagsHaveTheSameAdmissionMeaningAsBooleanFlags() {
    Map<String, Object> node = node();
    node.put("drained", 0);
    node.put("workspaceReachable", 1);
    assertTrue(policy.eligible(node, job()));
    node.put("drained", 1);
    assertFalse(policy.eligible(node, job()));
    node.put("drained", 0);
    node.put("workspaceReachable", 0);
    assertFalse(policy.eligible(node, job()));
  }

  @Test
  void cpuScoreRewardsFreeCapacityAsARatioOfNodeSize() {
    NodeSelectionPolicy cpuPolicy = policy(0, 0, 10, 0, 0);
    Map<String, Object> idle = node();
    Map<String, Object> partlyUsed = node();
    partlyUsed.put("cpuUsed", 4);
    assertTrue(cpuPolicy.score(idle, job(), NOW) > cpuPolicy.score(partlyUsed, job(), NOW));

    Map<String, Object> largerButBusier = node();
    largerButBusier.put("cpuTotal", 32);
    largerButBusier.put("cpuUsed", 16);
    assertTrue(
        cpuPolicy.score(partlyUsed, job(), NOW) > cpuPolicy.score(largerButBusier, job(), NOW),
        "the smaller node has a higher free ratio even though it has fewer free CPU cores");
  }

  @Test
  void memoryScoreRewardsFreeCapacityAsARatioOfNodeSize() {
    NodeSelectionPolicy memoryPolicy = policy(0, 0, 0, 10, 0);
    Map<String, Object> idle = node();
    Map<String, Object> partlyUsed = node();
    partlyUsed.put("memoryUsedGb", 8);
    assertTrue(memoryPolicy.score(idle, job(), NOW) > memoryPolicy.score(partlyUsed, job(), NOW));

    Map<String, Object> largerButBusier = node();
    largerButBusier.put("memoryTotalGb", 64);
    largerButBusier.put("memoryUsedGb", 32);
    assertTrue(
        memoryPolicy.score(partlyUsed, job(), NOW)
            > memoryPolicy.score(largerButBusier, job(), NOW),
        "the smaller node has a higher free ratio even though it has more limited total memory");
  }

  @Test
  void runningJobsLowerScoreWhenTheWorkloadPenaltyIsEnabled() {
    Map<String, Object> idle = node();
    Map<String, Object> busy = node();
    busy.put("runningJobs", List.of("CAE-1", "CAE-2"));
    NodeSelectionPolicy penalized = policy(0, 0, 0, 0, 5);
    assertTrue(penalized.score(idle, job(), NOW) > penalized.score(busy, job(), NOW));

    NodeSelectionPolicy unpenalized = policy(0, 0, 0, 0, 0);
    assertEquals(unpenalized.score(idle, job(), NOW), unpenalized.score(busy, job(), NOW));
  }

  @Test
  void configurableWeightsCanFavorUrgencyOrWaitingTime() {
    Map<String, Object> urgent = job();
    urgent.put("priority", 100);
    urgent.put("createdAt", NOW.toString());
    Map<String, Object> waiting = job();
    waiting.put("priority", 0);
    waiting.put("createdAt", NOW.minusSeconds(300).toString());

    NodeSelectionPolicy urgencyPolicy = policy(1, 0, 0, 0, 0);
    assertTrue(
        urgencyPolicy.score(node(), urgent, NOW) > urgencyPolicy.score(node(), waiting, NOW));
    NodeSelectionPolicy waitingPolicy = policy(0, 1, 0, 0, 0);
    assertTrue(
        waitingPolicy.score(node(), waiting, NOW) > waitingPolicy.score(node(), urgent, NOW));
  }

  @Test
  void futureCreatedTimeDoesNotProduceANegativeWaitingScore() {
    Map<String, Object> createdNow = job();
    createdNow.put("createdAt", NOW.toString());
    Map<String, Object> clockSkewed = job();
    clockSkewed.put("createdAt", NOW.plusSeconds(60).toString());
    NodeSelectionPolicy waitingPolicy = policy(0, 1, 0, 0, 0);
    assertEquals(
        waitingPolicy.score(node(), createdNow, NOW),
        waitingPolicy.score(node(), clockSkewed, NOW));
  }

  private static NodeSelectionPolicy policy(
      double priority, double waiting, double cpu, double memory, double workload) {
    return new NodeSelectionPolicy(
        new NodeSelectionPolicy.Weights(priority, waiting, cpu, memory, workload));
  }

  private Map<String, Object> node() {
    Map<String, Object> node = new HashMap<>();
    node.put("status", "ONLINE");
    node.put("drained", false);
    node.put("workspaceReachable", true);
    node.put("cpuTotal", 16);
    node.put("cpuUsed", 0);
    node.put("cpuReserved", 0);
    node.put("observedCpuUsed", 0);
    node.put("memoryTotalGb", 32);
    node.put("memoryUsedGb", 0);
    node.put("memoryReservedGb", 0);
    node.put("observedMemoryUsedGb", 0);
    node.put("solverTags", List.of("STRUCTURE", "NVH"));
    node.put("runningJobs", List.of());
    return node;
  }

  private Map<String, Object> job() {
    Map<String, Object> job = new HashMap<>();
    job.put("analysisType", "STRUCTURE");
    job.put("priority", 20);
    job.put("createdAt", NOW.minusSeconds(60).toString());
    job.put("resource", Map.of("cpu", 4, "memoryGb", 8));
    return job;
  }
}
