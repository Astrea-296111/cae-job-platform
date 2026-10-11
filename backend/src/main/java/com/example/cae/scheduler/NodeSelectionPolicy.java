package com.example.cae.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Predictable hard filters followed by a configurable, explainable score. */
@Component
public class NodeSelectionPolicy {
  public record Weights(
      double priority,
      double waitingSeconds,
      double freeCpu,
      double freeMemory,
      double runningPenalty) {}

  private final Weights weights;

  @Autowired
  public NodeSelectionPolicy(
      @Value("${cae.scheduler.weights.priority:1.0}") double priority,
      @Value("${cae.scheduler.weights.waiting-seconds:0.02}") double waitingSeconds,
      @Value("${cae.scheduler.weights.free-cpu:20.0}") double freeCpu,
      @Value("${cae.scheduler.weights.free-memory:20.0}") double freeMemory,
      @Value("${cae.scheduler.weights.running-penalty:5.0}") double runningPenalty) {
    this(new Weights(priority, waitingSeconds, freeCpu, freeMemory, runningPenalty));
  }

  public NodeSelectionPolicy(Weights weights) {
    for (double value :
        List.of(
            weights.priority(),
            weights.waitingSeconds(),
            weights.freeCpu(),
            weights.freeMemory(),
            weights.runningPenalty()))
      if (!Double.isFinite(value) || value < 0)
        throw new IllegalArgumentException("Scheduler weights must be finite nonnegative values");
    this.weights = weights;
  }

  public boolean eligible(Map<String, Object> node, Map<String, Object> job) {
    if (!"ONLINE".equals(node.get("status"))
        || truth(node.get("drained"))
        || !truth(node.get("workspaceReachable"))) return false;
    if (!(node.get("solverTags") instanceof List<?> tags)
        || !tags.contains(job.get("analysisType"))) return false;
    Map<?, ?> resource = (Map<?, ?>) job.get("resource");
    return freeCpu(node) >= number(resource, "cpu")
        && freeMemory(node) >= number(resource, "memoryGb");
  }

  public double score(Map<String, Object> node, Map<String, Object> job, Instant now) {
    long waiting = 0;
    Object created = job.get("createdAt");
    if (created != null)
      waiting = Math.max(0, Duration.between(Instant.parse(created.toString()), now).getSeconds());
    int running = node.get("runningJobs") instanceof List<?> jobs ? jobs.size() : 0;
    return number(job, "priority") * weights.priority()
        + waiting * weights.waitingSeconds()
        + ((double) freeCpu(node) / Math.max(1, number(node, "cpuTotal"))) * weights.freeCpu()
        + ((double) freeMemory(node) / Math.max(1, number(node, "memoryTotalGb")))
            * weights.freeMemory()
        - running * weights.runningPenalty();
  }

  public int freeCpu(Map<String, Object> node) {
    return number(node, "cpuTotal")
        - Math.max(number(node, "cpuUsed"), number(node, "observedCpuUsed"))
        - number(node, "cpuReserved");
  }

  public int freeMemory(Map<String, Object> node) {
    return number(node, "memoryTotalGb")
        - Math.max(number(node, "memoryUsedGb"), number(node, "observedMemoryUsedGb"))
        - number(node, "memoryReservedGb");
  }

  private int number(Map<?, ?> value, String field) {
    Object number = value.get(field);
    return number == null ? 0 : ((Number) number).intValue();
  }

  private boolean truth(Object value) {
    return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
  }
}
