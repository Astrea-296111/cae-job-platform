package com.example.cae.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.cae.common.ApiException;
import com.example.cae.common.FailureCode;
import com.example.cae.common.JsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ComputeAgentClientTest {
  private static final String TOKEN = "agent-client-test-token";
  private final JsonCodec json = new JsonCodec(new ObjectMapper());
  private final List<HttpServer> servers = new ArrayList<>();
  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void stopServers() {
    servers.forEach(server -> server.stop(0));
    executors.forEach(ExecutorService::shutdownNow);
  }

  @Test
  void acceptedButTimedOutSubmissionRemainsAmbiguousAndCanBeRecoveredByRequestId()
      throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger acceptedExecutions = new AtomicInteger();
    AtomicReference<String> executionRequestId = new AtomicReference<>();
    CountDownLatch releaseFirstResponse = new CountDownLatch(1);
    URI node =
        serve(
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/worker/v1/jobs/submit")) {
                Map<String, Object> request =
                    json.readMap(
                        new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String requestId = request.get("executionRequestId").toString();
                if (executionRequestId.compareAndSet(null, requestId))
                  acceptedExecutions.incrementAndGet();
                if (attempts.incrementAndGet() == 1) {
                  try {
                    releaseFirstResponse.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }
                respond(
                    exchange,
                    200,
                    json.write(
                        Map.of(
                            "externalExecutionId",
                            "node-a-000001",
                            "accepted",
                            true,
                            "state",
                            "SUBMITTED")));
              } else {
                respond(
                    exchange,
                    200,
                    json.write(
                        Map.of(
                            "externalExecutionId",
                            "node-a-000001",
                            "executionRequestId",
                            executionRequestId.get(),
                            "state",
                            "RUNNING")));
              }
            });
    ComputeAgentClient client = client(node, Duration.ofMillis(500));
    Map<String, Object> request =
        Map.of("executionRequestId", "exec-request-1", "jobNo", "CAE-20261010-000001");

    try {
      AgentException failure =
          assertThrows(AgentException.class, () -> client.submit("node-a", request));
      assertThat(failure.code()).isEqualTo(FailureCode.AGENT_TIMEOUT);
      assertThat(failure.ambiguous()).isTrue();
      assertThat(attempts.get()).isEqualTo(1);
      assertThat(client.queryByRequest("node-a", "exec-request-1"))
          .containsEntry("externalExecutionId", "node-a-000001")
          .containsEntry("state", "RUNNING");
      assertThat(client.submit("node-a", request))
          .containsEntry("externalExecutionId", "node-a-000001");
      assertThat(attempts.get()).isEqualTo(2);
      assertThat(acceptedExecutions.get()).isEqualTo(1);
    } finally {
      releaseFirstResponse.countDown();
    }
  }

  @Test
  void requestsSendServiceAuthenticationAndPreserveExecutionIdentifiers() throws Exception {
    List<Map<String, String>> received = java.util.Collections.synchronizedList(new ArrayList<>());
    URI node =
        serve(
            exchange -> {
              received.add(
                  Map.of(
                      "path",
                      exchange.getRequestURI().getPath(),
                      "method",
                      exchange.getRequestMethod(),
                      "token",
                      exchange.getRequestHeaders().getFirst("X-Service-Token"),
                      "body",
                      new String(
                          exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
              Map<String, Object> response =
                  exchange.getRequestURI().getPath().endsWith("/node/status")
                      ? Map.of(
                          "nodeId",
                          "node-a",
                          "online",
                          true,
                          "cpuTotal",
                          16,
                          "cpuUsed",
                          4,
                          "cpuReserved",
                          0,
                          "memoryTotalGb",
                          32,
                          "memoryUsedGb",
                          8,
                          "memoryReservedGb",
                          0,
                          "solverTags",
                          List.of("STRUCTURE"),
                          "runningJobs",
                          List.of("node-a-000017"))
                      : Map.of(
                          "externalExecutionId", "node-a-000017",
                          "executionRequestId", "exec-request-17",
                          "state", "RUNNING");
              respond(exchange, 200, json.write(response));
            });
    ComputeAgentClient client = client(node, Duration.ofSeconds(2));

    client.query("node-a", "node-a-000017");
    client.queryByRequest("node-a", "exec-request-17");
    client.cancel("node-a", "node-a-000017");
    client.nodeStatus("node-a");

    assertThat(received).extracting(row -> row.get("token")).containsOnly(TOKEN);
    assertThat(received)
        .extracting(row -> row.get("path"))
        .containsExactly(
            "/worker/v1/jobs/node-a-000017",
            "/worker/v1/jobs/by-request/exec-request-17",
            "/worker/v1/jobs/node-a-000017/cancel",
            "/worker/v1/node/status");
    assertThat(received.get(2)).containsEntry("method", "POST").containsEntry("body", "{}");
  }

  @Test
  void explicitRejectionAndRemoteNotFoundDoNotImplyAnAcceptedProcess() throws Exception {
    URI node =
        serve(
            exchange ->
                respond(exchange, exchange.getRequestMethod().equals("POST") ? 422 : 404, "{}"));
    ComputeAgentClient client = client(node, Duration.ofSeconds(2));
    AgentException rejected =
        assertThrows(AgentException.class, () -> client.submit("node-a", Map.of()));
    assertThat(rejected.code()).isEqualTo(FailureCode.SUBMIT_REJECTED);
    assertThat(rejected.ambiguous()).isFalse();
    AgentException missing =
        assertThrows(AgentException.class, () -> client.query("node-a", "unknown-execution"));
    assertThat(missing.notFound()).isTrue();
    assertThat(missing.ambiguous()).isFalse();
  }

  @Test
  void serverErrorAfterMutationRequiresReconciliationButQueryErrorDoesNot() throws Exception {
    URI node = serve(exchange -> respond(exchange, 503, "{}"));
    ComputeAgentClient client = client(node, Duration.ofSeconds(2));
    AgentException mutation =
        assertThrows(AgentException.class, () -> client.cancel("node-a", "node-a-1"));
    assertThat(mutation.code()).isEqualTo(FailureCode.UNKNOWN_REMOTE_STATE);
    assertThat(mutation.ambiguous()).isTrue();
    AgentException read =
        assertThrows(AgentException.class, () -> client.query("node-a", "node-a-1"));
    assertThat(read.ambiguous()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "{}", "{broken-json"})
  void malformedSuccessfulSubmissionResponseCannotBeTreatedAsRejection(String response)
      throws Exception {
    URI node = serve(exchange -> respond(exchange, 200, response));
    AgentException failure =
        assertThrows(
            AgentException.class,
            () -> client(node, Duration.ofSeconds(2)).submit("node-a", Map.of()));
    assertThat(failure.code()).isEqualTo(FailureCode.UNKNOWN_REMOTE_STATE);
    assertThat(failure.ambiguous()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"accepted\":true,\"state\":\"SUBMITTED\"}",
        "{\"accepted\":true,\"externalExecutionId\":\"../unsafe\",\"state\":\"SUBMITTED\"}",
        "{\"accepted\":true,\"externalExecutionId\":\"node-a-1\"}",
        "{\"accepted\":true,\"externalExecutionId\":\"node-a-1\",\"state\":\"CREATED\"}",
        "{\"accepted\":false,\"externalExecutionId\":\"node-a-1\",\"state\":\"SUBMITTED\"}",
        "{\"accepted\":\"true\",\"externalExecutionId\":\"node-a-1\",\"state\":\"SUBMITTED\"}"
      })
  void structurallyInvalidAcceptedResponseRemainsAnAmbiguousMutation(String response)
      throws Exception {
    URI node = serve(exchange -> respond(exchange, 202, response));
    AgentException failure =
        assertThrows(
            AgentException.class,
            () -> client(node, Duration.ofSeconds(2)).submit("node-a", Map.of()));
    assertThat(failure.code()).isEqualTo(FailureCode.UNKNOWN_REMOTE_STATE);
    assertThat(failure.ambiguous()).isTrue();
  }

  @Test
  void acceptedFastExecutionMayAlreadyBeSuccessfulWhenResponseArrives() throws Exception {
    URI node =
        serve(
            exchange ->
                respond(
                    exchange,
                    202,
                    "{\"accepted\":true,\"externalExecutionId\":\"node-a-1\",\"state\":\"SUCCESS\"}"));
    assertThat(client(node, Duration.ofSeconds(2)).submit("node-a", Map.of()))
        .containsEntry("externalExecutionId", "node-a-1")
        .containsEntry("state", "SUCCESS");
  }

  @Test
  void queryCannotReturnAnotherExecutionsFacts() throws Exception {
    URI node =
        serve(
            exchange ->
                respond(
                    exchange,
                    200,
                    "{\"externalExecutionId\":\"node-a-different\",\"executionRequestId\":\"wrong-request\",\"state\":\"RUNNING\"}"));
    ComputeAgentClient client = client(node, Duration.ofSeconds(2));
    AgentException byExternal =
        assertThrows(AgentException.class, () -> client.query("node-a", "node-a-expected"));
    assertThat(byExternal.code()).isEqualTo(FailureCode.UNKNOWN_REMOTE_STATE);
    assertThat(byExternal.ambiguous()).isFalse();
    AgentException byRequest =
        assertThrows(
            AgentException.class, () -> client.queryByRequest("node-a", "expected-request"));
    assertThat(byRequest.ambiguous()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:///tmp/secret",
        "ftp://localhost",
        "http://user:password@localhost",
        "http://localhost/api",
        "http://localhost/?query=value",
        "http://localhost/#fragment"
      })
  void credentialsCanOnlyBeSentToConfiguredHttpOrigins(String origin) {
    assertThrows(
        IllegalArgumentException.class, () -> client(URI.create(origin), Duration.ofSeconds(2)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"../secret", "execution/other", "execution?query=value", "execution%2Fother"})
  void unsafeIdentifiersAndUnknownNodesAreRejectedBeforeNetworkAccess(String identifier)
      throws Exception {
    AtomicInteger requests = new AtomicInteger();
    URI node =
        serve(
            exchange -> {
              requests.incrementAndGet();
              respond(exchange, 200, "{}");
            });
    ComputeAgentClient client = client(node, Duration.ofSeconds(2));
    assertThrows(ApiException.class, () -> client.query("node-a", identifier));
    assertThrows(ApiException.class, () -> client.submit("unconfigured-node", Map.of()));
    assertThat(requests.get()).isZero();
  }

  @Test
  void redirectsNeverLeakServiceTokenToAnotherOrigin() throws Exception {
    AtomicInteger redirectedRequests = new AtomicInteger();
    URI foreign =
        serve(
            exchange -> {
              redirectedRequests.incrementAndGet();
              respond(exchange, 200, "{\"accepted\":true}");
            });
    URI node =
        serve(
            exchange -> {
              exchange.getResponseHeaders().add("Location", foreign.resolve("/capture").toString());
              respond(exchange, 302, "{}");
            });

    AgentException failure =
        assertThrows(
            AgentException.class,
            () -> client(node, Duration.ofSeconds(2)).submit("node-a", Map.of()));
    assertThat(failure.httpStatus()).isEqualTo(302);
    assertThat(redirectedRequests.get()).isZero();
  }

  private ComputeAgentClient client(URI node, Duration timeout) {
    return new ComputeAgentClient(Map.of("node-a", node), TOKEN, timeout, json);
  }

  private URI serve(HttpHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/", handler);
    server.start();
    servers.add(server);
    executors.add(executor);
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void respond(HttpExchange exchange, int status, String response) throws IOException {
    byte[] body = response.getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
