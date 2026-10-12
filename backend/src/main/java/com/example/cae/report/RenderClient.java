package com.example.cae.report;

import com.example.cae.agent.ComputeAgentClient;
import com.example.cae.common.JsonCodec;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Rendering has its own timeout and never sends credentials to an unconfigured origin. */
@Component
public class RenderClient {
  private final ComputeAgentClient agents;
  private final JsonCodec json;
  private final String token;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public RenderClient(
      ComputeAgentClient agents,
      JsonCodec json,
      @Value("${SERVICE_TOKEN:demo-service-token}") String token) {
    this.agents = agents;
    this.json = json;
    this.token = token;
  }

  public Map<String, Object> render(String node, String kind, Map<String, Object> payload) {
    var origin = agents.nodes().get(node);
    if (origin == null) throw new IllegalArgumentException("Render node is not configured");
    var request =
        HttpRequest.newBuilder(
                origin.resolve(
                    kind.equals("POST")
                        ? "/worker/v1/post/process"
                        : "/worker/v1/reports/generate"))
            .timeout(Duration.ofSeconds(30))
            .header("X-Service-Token", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.write(payload)))
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalArgumentException("Renderer returned HTTP " + response.statusCode());
      if (response.body().length() > 2 * 1024 * 1024)
        throw new IllegalArgumentException("Render response is too large");
      return json.readMap(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Rendering interrupted", interrupted);
    } catch (IOException unavailable) {
      throw new IllegalArgumentException(
          "Render response unavailable; the same frozen stage can be replayed", unavailable);
    }
  }
}
