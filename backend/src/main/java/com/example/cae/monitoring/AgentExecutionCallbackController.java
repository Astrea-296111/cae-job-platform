package com.example.cae.monitoring;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ServiceTokenFilter authenticates this internal route independently of browser authentication. */
@RestController
@RequestMapping("/internal/v1/agents")
public class AgentExecutionCallbackController {
  private final RemoteExecutionStateService states;

  public AgentExecutionCallbackController(RemoteExecutionStateService states) {
    this.states = states;
  }

  @PostMapping("/{nodeId}/executions/callback")
  public RemoteExecutionStateService.ApplyResult callback(
      @PathVariable String nodeId, @RequestBody Map<String, Object> observation) {
    return states.callback(nodeId, observation);
  }
}
