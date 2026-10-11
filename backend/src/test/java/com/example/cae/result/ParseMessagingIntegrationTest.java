package com.example.cae.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.cae.messaging.CommandEnvelope;
import com.example.cae.messaging.RabbitTopology;
import com.example.cae.outbox.OutboxPublisher;
import com.example.cae.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Actual broker ACK/DLQ with isolated vhost; scoped publication never dispatches unrelated Jobs.
 */
@SpringBootTest(
    properties = {
      "cae.mq.listeners-enabled=true",
      "cae.mq.max-delivery-attempts=3",
      "cae.mq.retry-base-ms=500"
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ParseMessagingIntegrationTest extends ResultTestSupport {
  private static final String QUEUE = ParseCommandListener.QUEUE;
  private static final String CONSUMER = ParseCommandListener.CONSUMER;
  @Autowired ResultRepository results;
  @Autowired ResultParsingService parsing;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxPublisher publisher;
  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin admin;
  @Autowired CachingConnectionFactory connection;
  @Autowired MeterRegistry metrics;

  @BeforeEach
  void prepareOnlyOwnedParseQueue() {
    assertThat(connection.getVirtualHost()).startsWith("cae_test_");
    admin.purgeQueue(QUEUE, false);
    admin.purgeQueue(QUEUE + ".dlq", false);
  }

  @Test
  void sameAndDifferentLogicalDeliveriesAcknowledgeExactlyOneParserVersion() throws Exception {
    Fixture fixture = completed();
    long originalId = parseCommand(fixture);
    CommandEnvelope original = envelope(originalId);
    long repeatedPhysical = append(original.retry(0));
    CommandEnvelope differentLogical =
        CommandEnvelope.create(
            "RESULT_PARSE_REQUESTED", fixture.jobId(), fixture.jobNo(), original.payload());
    long anotherId = append(differentLogical);
    assertThat(publisher.publishIds(List.of(originalId, repeatedPhysical, anotherId))).isEqualTo(3);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(inboxStatus(original)).isEqualTo("DONE");
              assertThat(inboxStatus(differentLogical)).isEqualTo("DONE");
              assertThat(results.history(fixture.jobId())).hasSize(1);
              assertThat(results.metrics(fixture.jobId(), "v1")).hasSize(7);
              assertThat(postCommands(fixture)).isEqualTo(1);
            });
    // Once both logical commands are durably DONE, a later duplicate must be a no-op.
    double acked = metrics.counter("cae.mq.parse.acked").count();
    long late = append(original.retry(0));
    assertThat(publisher.publishIds(List.of(late))).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(metrics.counter("cae.mq.parse.acked").count()).isGreaterThan(acked);
              assertThat(postCommands(fixture)).isEqualTo(1);
            });
    assertThat(results.metrics(fixture.jobId(), "v1")).hasSize(7);
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow())
        .containsEntry("status", "COMPLETE");
    assertThat(jobs.findById(fixture.jobId())).containsEntry("status", "POST_PROCESSING");
    assertThat(rabbit.receive(QUEUE + ".dlq")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"schema", "numericStorageOverflow"})
  void
      corruptRawSchemaOrFiniteStorageOverflowRetriesBoundedlyThenActualDeadLettersWithoutMetricsOrPost(
          String corruption) throws Exception {
    Fixture fixture = completed();
    if (corruption.equals("schema")) rewrite(fixture, "raw_result.json", "schemaVersion", 999);
    else rewrite(fixture, "raw_result.json", "mass_kg", new BigDecimal("1e100"));
    publishUntilDead(fixture);
    assertThat(results.metrics(fixture.jobId(), null)).isEmpty();
    assertThat(postCommands(fixture)).isZero();
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow()).containsEntry("status", "FAILED");
    assertThat(jobs.findById(fixture.jobId()))
        .containsEntry("status", "POST_PROCESS_FAILED")
        .containsEntry("failureCode", "POST_PROCESS_FAILED");
    assertThat(executions.history(fixture.jobId())).hasSize(1);
  }

  @Test
  void actualNonTransientMetricUniquenessViolationUsesBoundedBusinessFailureAndDeadLetters()
      throws Exception {
    Fixture fixture = completed();
    String checksum = sha(fixture.workspace().resolve("raw_result.json"));
    Map<String, Object> requested =
        tx.execute(
            t ->
                results.request(
                    fixture.jobId(),
                    fixture.executionId(),
                    "v1",
                    checksum,
                    "STRUCTURE",
                    "BIW_TORSION",
                    3,
                    "COMPLETE"));
    // Inject a historical partial metric set in the owned schema. The parser's real INSERT
    // must hit MySQL's unique constraint; a durable data conflict is not a connection outage.
    jdbc.update(
        "INSERT INTO"
            + " result_metric(parse_record_id,job_id,execution_id,parser_version,metric_code,metric_name,value_num,unit,dimension,status,condition_code,subcase)"
            + " VALUES(?,?,?,'v1','MASS','Synthetic preexisting"
            + " mass',315.125,'kg','MASS','VALID','BIW_TORSION',3)",
        requested.get("id"),
        fixture.jobId(),
        fixture.executionId());
    List<Map<String, Object>> original = results.metrics(fixture.jobId(), "v1");
    assertThatThrownBy(() -> parsing.parse(fixture.jobId(), fixture.executionId(), "v1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasCauseInstanceOf(DataIntegrityViolationException.class);
    publishUntilDead(fixture);
    assertThat(results.metrics(fixture.jobId(), "v1")).isEqualTo(original);
    assertThat(postCommands(fixture)).isZero();
    assertThat(results.find(fixture.jobId(), "v1").orElseThrow()).containsEntry("status", "FAILED");
    assertThat(jobs.findById(fixture.jobId()))
        .containsEntry("status", "POST_PROCESS_FAILED")
        .containsEntry("failureCode", "POST_PROCESS_FAILED");
  }

  private void publishUntilDead(Fixture fixture) {
    long originalId = parseCommand(fixture);
    CommandEnvelope original = envelope(originalId);
    assertThat(publisher.publishIds(List.of(originalId))).isEqualTo(1);
    for (int failures = 1; failures <= 3; failures++) {
      int expected = failures;
      await()
          .atMost(Duration.ofSeconds(8))
          .untilAsserted(
              () -> {
                assertThat(inboxStatus(original)).isEqualTo(expected == 3 ? "DEAD" : "RETRY");
                assertThat(
                        jdbc.queryForObject(
                            "SELECT retry_count FROM cae_message_inbox WHERE logical_event_id=? AND"
                                + " consumer_name=?",
                            Integer.class,
                            original.eventId(),
                            CONSUMER))
                    .isEqualTo(expected);
              });
      if (failures < 3) {
        long retry =
            jdbc.queryForObject(
                "SELECT id FROM cae_outbox_event WHERE logical_event_id=? AND delivery_attempt=?",
                Long.class,
                original.eventId(),
                failures);
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> publisher.publishIds(List.of(retry)) == 1);
      }
    }
    Message dead = rabbit.receive(QUEUE + ".dlq", 5000);
    assertThat(dead).isNotNull();
    CommandEnvelope deadCommand =
        CommandEnvelope.from(json.readMap(new String(dead.getBody(), StandardCharsets.UTF_8)));
    assertThat(deadCommand.eventId()).isEqualTo(original.eventId());
    assertThat(deadCommand.deliveryAttempt()).isEqualTo(2);
    assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    List<?> deaths = (List<?>) dead.getMessageProperties().getHeaders().get("x-death");
    assertThat(deaths).hasSize(1);
    assertThat(((Map<?, ?>) deaths.get(0)).get("reason").toString()).isEqualTo("rejected");
    assertThat(((Map<?, ?>) deaths.get(0)).get("queue").toString()).isEqualTo(QUEUE);
  }

  private long parseCommand(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT id FROM cae_outbox_event WHERE aggregate_id=? AND"
            + " event_type='RESULT_PARSE_REQUESTED' AND exchange_name='cae.command' ORDER BY id"
            + " LIMIT 1",
        Long.class,
        fixture.jobId());
  }

  private long append(CommandEnvelope envelope) {
    tx.executeWithoutResult(
        s -> outbox.append(envelope, RabbitTopology.COMMAND_EXCHANGE, "job.parse", Instant.now()));
    return jdbc.queryForObject(
        "SELECT id FROM cae_outbox_event WHERE event_id=?", Long.class, envelope.deliveryId());
  }

  private CommandEnvelope envelope(long id) {
    return CommandEnvelope.from(
        json.readMap(
            jdbc.queryForObject(
                "SELECT payload FROM cae_outbox_event WHERE id=?", String.class, id)));
  }

  private String inboxStatus(CommandEnvelope envelope) {
    List<String> statuses =
        jdbc.queryForList(
            "SELECT status FROM cae_message_inbox WHERE logical_event_id=? AND consumer_name=?",
            String.class,
            envelope.eventId(),
            CONSUMER);
    return statuses.isEmpty() ? "ABSENT" : statuses.get(0);
  }
}
