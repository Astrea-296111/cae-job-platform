package com.example.cae.resource;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/compute-nodes")
public class ComputeNodeController {
  private final NodeRegistryService registry;

  public ComputeNodeController(NodeRegistryService registry) {
    this.registry = registry;
  }

  @GetMapping
  public List<Map<String, Object>> nodes() {
    return registry.nodes();
  }

  @GetMapping("/{nodeId}")
  public Map<String, Object> node(@PathVariable String nodeId) {
    return registry.cached(nodeId);
  }

  public record Drain(boolean drained) {}

  @PatchMapping("/{nodeId}/drain")
  public Map<String, Object> drain(
      Authentication user, @PathVariable String nodeId, @RequestBody Drain request) {
    return registry.drain(user, nodeId, request.drained());
  }
}
