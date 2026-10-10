package com.example.cae.agent;

import com.example.cae.common.ApiException;
import com.example.cae.common.FailureCode;
import com.example.cae.common.JsonCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only configured Agent URLs can receive control-plane requests and service credentials. */
@Component
public class ComputeAgentClient {
  private static final Set<String> REMOTE_STATES =
      Set.of(
          "SUBMITTED", "RUNNING", "SUCCESS", "RUNTIME_FAILED", "TIMEOUT", "CANCELLED", "UNKNOWN");
  private final Map<String, URI> nodes;
  private final String token;
  private final Duration timeout;
  private final JsonCodec json;
  private final HttpClient client;

  @Autowired
  public ComputeAgentClient(
      @Value(
              "${WORKER_URLS:node-a=http://127.0.0.1:8001,node-b=http://127.0.0.1:8002,node-c=http://127.0.0.1:8003}")
          String configuredNodes,
      @Value("${SERVICE_TOKEN:demo-service-token}") String token,
      @Value("${cae.agent.request-timeout-ms:2000}") long timeoutMs,
      JsonCodec json) {
    this(parseNodes(configuredNodes), token, Duration.ofMillis(timeoutMs), json);
  }

  public ComputeAgentClient(
      Map<String, URI> nodes, String token, Duration timeout, JsonCodec json) {
    if (nodes.isEmpty()
        || token == null
        || token.isBlank()
        || timeout.isZero()
        || timeout.isNegative())
      throw new IllegalArgumentException("Agent configuration is incomplete");
    nodes.forEach(
        (id, uri) -> {
          identifier(id);
          if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
              || uri.getHost() == null
              || uri.getUserInfo() != null
              || uri.getQuery() != null
              || uri.getFragment() != null
              || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Agent base URL must be a plain HTTP(S) origin");
        });
    this.nodes = Map.copyOf(nodes);
    this.token = token;
    this.timeout = timeout;
    this.json = json;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Map<String, URI> nodes() {
    return nodes;
  }

  public Map<String, Object> submit(String nodeId, Map<String, Object> request) {
    Map<String, Object> result = request(nodeId, "POST", "/worker/v1/jobs/submit", request, true);
    if (!Boolean.TRUE.equals(result.get("accepted"))) throw invalidResponse(true);
    validateExecution(result, null, null, true);
    return result;
  }

  public Map<String, Object> query(String nodeId, String externalExecutionId) {
    Map<String, Object> result =
        request(nodeId, "GET", "/worker/v1/jobs/" + identifier(externalExecutionId), null, false);
    validateExecution(result, externalExecutionId, null, false);
    return result;
  }

  public Map<String, Object> queryByRequest(String nodeId, String executionRequestId) {
    Map<String, Object> result =
        request(
            nodeId,
            "GET",
            "/worker/v1/jobs/by-request/" + identifier(executionRequestId),
            null,
            false);
    validateExecution(result, null, executionRequestId, false);
    return result;
  }

  public Map<String, Object> cancel(String nodeId, String externalExecutionId) {
    Map<String, Object> result =
        request(
            nodeId,
            "POST",
            "/worker/v1/jobs/" + identifier(externalExecutionId) + "/cancel",
            Map.of(),
            true);
    validateExecution(result, externalExecutionId, null, true);
    return result;
  }

  public Map<String, Object> nodeStatus(String nodeId) {
    Map<String, Object> result = request(nodeId, "GET", "/worker/v1/node/status", null, false);
    if (!nodeId.equals(result.get("nodeId"))) throw invalidResponse(false);
    return result;
  }

  private static void validateExecution(
      Map<String, Object> result, String externalId, String requestId, boolean mutating) {
    Object actualId = result.get("externalExecutionId");
    Object state = result.get("state");
    if (!(actualId instanceof String id)
        || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        || !(state instanceof String status)
        || !REMOTE_STATES.contains(status)
        || (externalId != null && !externalId.equals(id))
        || (requestId != null && !requestId.equals(result.get("executionRequestId"))))
      throw invalidResponse(mutating);
  }

  private static AgentException invalidResponse(boolean mutating) {
    return new AgentException(
        FailureCode.UNKNOWN_REMOTE_STATE,
        "Agent response violates the execution protocol",
        200,
        mutating);
  }

  private Map<String, Object> request(
      String nodeId, String method, String path, Map<String, Object> body, boolean mutating) {
    URI origin = nodes.get(nodeId);
    if (origin == null) throw ApiException.invalid("Unknown compute node");
    HttpRequest.Builder request =
        HttpRequest.newBuilder(origin.resolve(path))
            .timeout(timeout)
            .header("X-Service-Token", token)
            .header("Accept", "application/json");
    if (body == null) request.GET();
    else
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json.write(body)));
    try {
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        boolean uncertain = mutating && response.statusCode() >= 500;
        throw new AgentException(
            uncertain ? FailureCode.UNKNOWN_REMOTE_STATE : FailureCode.SUBMIT_REJECTED,
            "Agent returned HTTP " + response.statusCode(),
            response.statusCode(),
            uncertain);
      }
      try {
        Map<String, Object> result = json.readMap(response.body());
        if (result == null || result.isEmpty())
          throw new IllegalArgumentException("Empty Agent response");
        return result;
      } catch (IllegalArgumentException e) {
        throw new AgentException(
            FailureCode.UNKNOWN_REMOTE_STATE,
            "Agent response is not a JSON object",
            response.statusCode(),
            mutating);
      }
    } catch (HttpTimeoutException e) {
      throw new AgentException(FailureCode.AGENT_TIMEOUT, "Agent request timed out", 0, mutating);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentException(
          FailureCode.UNKNOWN_REMOTE_STATE, "Agent request interrupted", 0, mutating);
    } catch (IOException e) {
      throw new AgentException(
          FailureCode.NODE_OFFLINE, "Agent connection unavailable", 0, mutating);
    }
  }

  private static Map<String, URI> parseNodes(String configuration) {
    Map<String, URI> nodes = new LinkedHashMap<>();
    for (String entry : configuration.split(",")) {
      String[] pair = entry.trim().split("=", 2);
      if (pair.length != 2 || nodes.putIfAbsent(pair[0], URI.create(pair[1])) != null)
        throw new IllegalArgumentException("Agent nodes must be unique node-id=URL entries");
    }
    return nodes;
  }

  private static String identifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
      throw ApiException.invalid("Invalid Agent identifier");
    return value;
  }
}
