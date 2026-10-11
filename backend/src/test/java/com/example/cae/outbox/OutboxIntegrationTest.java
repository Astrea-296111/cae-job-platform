package com.example.cae.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.messaging.CommandEnvelope;
import com.example.cae.messaging.RabbitTopology;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Tests use owned row IDs, never a global publisher scan against other integration fixtures. */
@SpringBootTest(properties = {"cae.background.enabled=false", "cae.mq.listeners-enabled=false"})
class OutboxIntegrationTest {
  @Autowired JobRepository jobs;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  @Test
  void rollbackRemovesTheJobItsJournalAndTheCommandOutbox() {
    AtomicReference<String> number = new AtomicReference<>();
    AtomicReference<Long> id = new AtomicReference<>();
    assertThatThrownBy(
            () ->
                tx.execute(
                    transaction -> {
                      Map<String, Object> job = newJob();
                      number.set(job.get("jobNo").toString());
                      id.set(SqlRows.id(job));
                      job =
                          jobs.transition(
                              job, JobStatus.VALIDATING, "VALIDATION_STARTED", Map.of());
                      job = jobs.transition(job, JobStatus.READY, "JOB_READY", Map.of());
                      jobs.transition(job, JobStatus.QUEUED, "JOB_SUBMIT_REQUESTED", Map.of());
                      assertThat(count("cae_job_event", "job_id", id.get())).isEqualTo(4);
                      assertThat(count("cae_outbox_event", "aggregate_id", id.get())).isEqualTo(5);
                      throw new IllegalStateException("controlled rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled rollback");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_job WHERE job_no=?", Integer.class, number.get()))
        .isZero();
    assertThat(count("cae_job_event", "job_id", id.get())).isZero();
    assertThat(count("cae_outbox_event", "aggregate_id", id.get())).isZero();
  }

  @Test
  void committedTransitionCreatesJournalAndOutboxInTheSameTransaction() {
    Map<String, Object> job = newJob();
    long id = SqlRows.id(job);
    tx.executeWithoutResult(
        transaction -> {
          Map<String, Object> validating =
              jobs.transition(
                  job, JobStatus.VALIDATING, "VALIDATION_STARTED", Map.of("source", "integration"));
          Map<String, Object> ready =
              jobs.transition(validating, JobStatus.READY, "JOB_READY", Map.of());
          jobs.transition(ready, JobStatus.QUEUED, "JOB_SUBMIT_REQUESTED", Map.of());
        });
    assertThat(jobs.findById(id)).containsEntry("status", "QUEUED");
    assertThat(jobs.events(id))
        .extracting(event -> event.get("eventType"))
        .containsExactly("JOB_CREATED", "VALIDATION_STARTED", "JOB_READY", "JOB_SUBMIT_REQUESTED");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM cae_outbox_event WHERE aggregate_id=? ORDER BY id",
                String.class,
                id))
        .containsExactly(
            "JOB_CREATED",
            "VALIDATION_STARTED",
            "JOB_READY",
            "JOB_SUBMIT_REQUESTED",
            "JOB_SUBMIT_REQUESTED");
  }

  @Test
  void concurrentPublisherSkipsAnOwnedRowLockedByAnotherTransaction() throws Exception {
    Map<String, Object> job = newJob();
    long first = append(job, "lease.first");
    long second = append(job, "lease.second");
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var thread = Executors.newSingleThreadExecutor();
    try {
      var holding =
          thread.submit(
              () ->
                  tx.execute(
                      transaction -> {
                        var rows =
                            outbox.claimIds("holder", List.of(first), Duration.ofSeconds(30));
                        assertThat(rows)
                            .extracting(OutboxRepository.ClaimedEvent::id)
                            .containsExactly(first);
                        claimed.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("lease fixture timeout");
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(error);
                        }
                        return rows;
                      }));
      assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(outbox.claimIds("contender", List.of(first, second), Duration.ofSeconds(30)))
          .extracting(OutboxRepository.ClaimedEvent::id)
          .containsExactly(second);
      release.countDown();
      assertThat(holding.get(5, TimeUnit.SECONDS)).hasSize(1);
    } finally {
      release.countDown();
      thread.shutdownNow();
    }
  }

  @Test
  void expiredLeaseIsReclaimedAndOldOwnerCannotMarkOrRetryTheNewClaim() {
    long id = append(newJob(), "lease.expired");
    var original = outbox.claimIds("old-owner", List.of(id), Duration.ofSeconds(30)).get(0);
    jdbc.update(
        "UPDATE cae_outbox_event SET claim_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",
        id);
    var replacement = outbox.claimIds("new-owner", List.of(id), Duration.ofSeconds(30)).get(0);
    assertThat(outbox.markSent(original)).isFalse();
    assertThat(outbox.retryOrDead(original, "stale process", 3, Duration.ofMillis(10))).isFalse();
    assertThat(outbox.markSent(replacement)).isTrue();
    assertThat(
            jdbc.queryForObject("SELECT status FROM cae_outbox_event WHERE id=?", String.class, id))
        .isEqualTo("SENT");
  }

  @Test
  void brokerConfirmForAnUnroutableMandatoryPublicationDoesNotMarkItSent() {
    long id = append(newJob(), "unbound." + UUID.randomUUID());
    assertThat(publisher.publishIds(List.of(id))).isZero();
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status,retry_count,last_error,published_at FROM cae_outbox_event WHERE id=?",
            id);
    assertThat(row).containsEntry("status", "RETRY").containsEntry("retry_count", 1);
    assertThat(row.get("last_error").toString()).contains("unroutable");
    assertThat(row.get("published_at")).isNull();
  }

  private long append(Map<String, Object> job, String routing) {
    CommandEnvelope envelope =
        CommandEnvelope.create(
            "LEASE_TEST", SqlRows.id(job), job.get("jobNo").toString(), Map.of());
    tx.executeWithoutResult(
        transaction ->
            outbox.append(envelope, RabbitTopology.COMMAND_EXCHANGE, routing, Instant.now()));
    return jdbc.queryForObject(
        "SELECT id FROM cae_outbox_event WHERE event_id=?", Long.class, envelope.deliveryId());
  }

  private int count(String table, String key, long id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + key + "=?", Integer.class, id);
  }

  private Map<String, Object> newJob() {
    return jobs.create(
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
            Map.entry("casePath", "outbox-fixture"),
            Map.entry("resource", Map.of("cpu", 4, "memoryGb", 8, "walltimeMin", 30))));
  }
}
