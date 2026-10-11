package com.example.cae.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.repository.JobRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"cae.background.enabled=false", "cae.mq.listeners-enabled=false"})
class InboxIntegrationTest {
  @Autowired InboxRepository inbox;
  @Autowired JobRepository jobs;
  @Autowired JdbcTemplate jdbc;

  @Test
  void concurrentPhysicalDeliveriesAcquireOneLogicalHandlingLeaseAndDoneRemainsDone()
      throws Exception {
    CommandEnvelope command = command();
    var pool = Executors.newFixedThreadPool(10);
    List<InboxRepository.Claim> claims = new ArrayList<>();
    try {
      List<Callable<InboxRepository.Claim>> calls = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        CommandEnvelope delivery = command.retry(0);
        calls.add(() -> inbox.claim(delivery, "owned-inbox-test", Duration.ofSeconds(30)));
      }
      for (var result : pool.invokeAll(calls)) claims.add(result.get());
    } finally {
      pool.shutdownNow();
    }
    assertThat(claims)
        .filteredOn(claim -> claim.disposition() == InboxRepository.Disposition.ACQUIRED)
        .hasSize(1);
    assertThat(claims)
        .filteredOn(claim -> claim.disposition() == InboxRepository.Disposition.BUSY)
        .hasSize(9);
    var accepted =
        claims.stream()
            .filter(claim -> claim.disposition() == InboxRepository.Disposition.ACQUIRED)
            .findFirst()
            .orElseThrow();
    inbox.complete(command, "owned-inbox-test", accepted.owner());
    assertThat(
            inbox.claim(command.retry(1), "owned-inbox-test", Duration.ofSeconds(30)).disposition())
        .isEqualTo(InboxRepository.Disposition.DONE);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_message_inbox WHERE logical_event_id=?",
                Integer.class,
                command.eventId()))
        .isEqualTo(1);
  }

  @Test
  void staleOwnerIsFencedAndAnExhaustionCallbackRollsBackWithItsInboxUpdate() {
    CommandEnvelope command = command();
    String consumer = "owned-inbox-rollback";
    var first = inbox.claim(command, consumer, Duration.ofSeconds(30));
    jdbc.update(
        "UPDATE cae_message_inbox SET claim_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE logical_event_id=?",
        command.eventId());
    var replacement = inbox.claim(command.retry(0), consumer, Duration.ofSeconds(30));
    assertThatThrownBy(() -> inbox.complete(command, consumer, first.owner()))
        .isInstanceOf(InboxRepository.LostLeaseException.class);
    assertThatThrownBy(
            () ->
                inbox.fail(
                    command, consumer, first.owner(), "stale", 2, Duration.ofSeconds(1), () -> {}))
        .isInstanceOf(InboxRepository.LostLeaseException.class);
    assertThat(
            inbox.fail(
                command,
                consumer,
                replacement.owner(),
                "controlled failure",
                2,
                Duration.ofSeconds(1),
                () -> {}))
        .isEqualTo(InboxRepository.FailureDisposition.RETRY);
    assertThat(inbox.claim(command.retry(1), consumer, Duration.ofSeconds(30)).disposition())
        .isEqualTo(InboxRepository.Disposition.BUSY);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_outbox_event WHERE logical_event_id=?",
                Integer.class,
                command.eventId()))
        .isEqualTo(1);

    jdbc.update(
        "UPDATE cae_message_inbox SET next_retry_time=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE logical_event_id=?",
        command.eventId());
    var next = inbox.claim(command.retry(1), consumer, Duration.ofSeconds(30));
    assertThatThrownBy(
            () ->
                inbox.fail(
                    command,
                    consumer,
                    next.owner(),
                    "exhausted",
                    2,
                    Duration.ofSeconds(1),
                    () -> {
                      jobs.transition(
                          command.jobId(), JobStatus.VALIDATING, "SHOULD_ROLL_BACK", Map.of());
                      throw new IllegalStateException("controlled exhaustion rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled exhaustion rollback");
    assertThat(jobs.findById(command.jobId())).containsEntry("status", "CREATED");
    assertThat(jobs.events(command.jobId()))
        .extracting(event -> event.get("eventType"))
        .containsExactly("JOB_CREATED");
    assertThat(
            jdbc.queryForMap(
                "SELECT status,retry_count FROM cae_message_inbox WHERE logical_event_id=?",
                command.eventId()))
        .containsEntry("status", "PROCESSING")
        .containsEntry("retry_count", 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_outbox_event WHERE logical_event_id=?",
                Integer.class,
                command.eventId()))
        .isEqualTo(1);
    inbox.complete(command, consumer, next.owner());
  }

  private CommandEnvelope command() {
    Map<String, Object> job =
        jobs.create(
            Map.ofEntries(
                Map.entry("projectId", 1L),
                Map.entry("modelRevisionId", 2L),
                Map.entry("analysisType", "STRUCTURE"),
                Map.entry("conditionCode", "BIW_TORSION"),
                Map.entry("solverType", "OPTISTRUCT"),
                Map.entry("solverProfile", "STRUCTURE_STANDARD"),
                Map.entry("priority", 50),
                Map.entry("submitterId", "engineer"),
                Map.entry("resourceProfileId", "SMALL"),
                Map.entry("casePath", "inbox-fixture"),
                Map.entry("resource", Map.of("cpu", 4, "memoryGb", 8, "walltimeMin", 30))));
    return CommandEnvelope.create(
        "JOB_SUBMIT_REQUESTED", SqlRows.id(job), job.get("jobNo").toString(), Map.of());
  }
}
