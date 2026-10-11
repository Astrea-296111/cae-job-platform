package com.example.cae.agent;

import com.example.cae.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives authenticated observations; scheduling and durable resource accounting consume the
 * event.
 */
@RestController
@RequestMapping("/internal/v1/agents")
public class AgentHeartbeatController {
  public record Payload(
      @NotBlank String nodeId,
      @NotBlank @Size(max = 128) String hostname,
      @NotNull Boolean online,
      Boolean workspaceReachable,
      @Min(1) @Max(1024) int cpuTotal,
      @Min(0) int cpuUsed,
      @Min(0) int cpuReserved,
      @Min(1) @Max(4096) int memoryTotalGb,
      @Min(0) int memoryUsedGb,
      @Min(0) int memoryReservedGb,
      @NotNull @Size(max = 32) List<@NotBlank String> solverTags,
      @NotNull @Size(max = 10000) List<@NotBlank String> runningJobs) {}

  public record Observation(String nodeId, Instant receivedAt, Payload payload) {}

  private final ComputeAgentClient agents;
  private final ApplicationEventPublisher events;
  private final Map<String, Observation> observations = new ConcurrentHashMap<>();

  public AgentHeartbeatController(ComputeAgentClient agents, ApplicationEventPublisher events) {
    this.agents = agents;
    this.events = events;
  }

  @PostMapping("/{nodeId}/heartbeat")
  public Map<String, Object> receive(
      @PathVariable String nodeId, @Valid @RequestBody Payload payload) {
    if (!agents.nodes().containsKey(nodeId) || !nodeId.equals(payload.nodeId()))
      throw ApiException.invalid("Heartbeat node must match a configured node");
    if ((long) payload.cpuUsed() + payload.cpuReserved() > payload.cpuTotal()
        || (long) payload.memoryUsedGb() + payload.memoryReservedGb() > payload.memoryTotalGb())
      throw ApiException.invalid("Heartbeat usage exceeds node capacity");
    Observation observation = new Observation(nodeId, Instant.now(), payload);
    events.publishEvent(observation);
    observations.put(nodeId, observation);
    return Map.of("accepted", true, "nodeId", nodeId, "receivedAt", observation.receivedAt());
  }

  @GetMapping("/{nodeId}/heartbeat")
  public Observation latest(@PathVariable String nodeId) {
    Observation observation = observations.get(nodeId);
    if (observation == null) throw ApiException.notFound("Agent heartbeat");
    return observation;
  }
}
