package com.example.cae.scheduler;

import com.example.cae.common.SqlRows;
import com.example.cae.resource.NodeRegistryService;
import com.example.cae.resource.ResourceReservationService;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulerService {
  private final NodeRegistryService nodes;
  private final NodeSelectionPolicy policy;
  private final ResourceReservationService resources;

  public SchedulerService(
      NodeRegistryService nodes, NodeSelectionPolicy policy, ResourceReservationService resources) {
    this.nodes = nodes;
    this.policy = policy;
    this.resources = resources;
  }

  /**
   * Caller owns Job locking, execution creation, state transition and the encompassing transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ResourceReservationService.Reservation> allocate(Map<String, Object> job) {
    Instant now = Instant.now();
    var eligible =
        nodes.nodes().stream()
            .filter(node -> policy.eligible(node, job))
            .sorted(
                Comparator.<Map<String, Object>>comparingDouble(
                        node -> policy.score(node, job, now))
                    .reversed()
                    .thenComparing(node -> node.get("nodeId").toString()))
            .toList();
    Map<?, ?> resource = (Map<?, ?>) job.get("resource");
    String feature =
        job.get("licenseFeature") == null
            ? feature(job.get("solverType").toString())
            : job.get("licenseFeature").toString();
    int tokens = job.get("licenseTokenCount") instanceof Number value ? value.intValue() : 1;
    for (var node : eligible) {
      var reservation =
          resources.reserve(
              SqlRows.id(job),
              node.get("nodeId").toString(),
              ((Number) resource.get("cpu")).intValue(),
              ((Number) resource.get("memoryGb")).intValue(),
              feature,
              tokens);
      if (reservation.isPresent()) return reservation;
    }
    return Optional.empty();
  }

  public static String feature(String solver) {
    return switch (solver) {
      case "MOCK_OPTISTRUCT" -> "OPTISTRUCT";
      case "MOCK_NVH" -> "NVH";
      case "MOCK_CRASH" -> "CRASH";
      case "MOCK_CFD" -> "CFD";
      default -> throw new IllegalArgumentException("Unsupported solver type");
    };
  }
}
