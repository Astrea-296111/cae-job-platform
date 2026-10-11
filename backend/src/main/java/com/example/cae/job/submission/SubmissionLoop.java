package com.example.cae.job.submission;

import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.repository.JobRepository;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** M4 direct dispatch; M5 replaces remote submissions with durable queue commands. */
@Component
public class SubmissionLoop {
  private static final Logger LOG = LoggerFactory.getLogger(SubmissionLoop.class);
  private final JobRepository jobs;
  private final JobCommandService commands;
  private final JobSubmissionService submission;
  private final JobCancellationService cancellation;
  private final boolean enabled;
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
  private final ExecutorService executor = Executors.newFixedThreadPool(4);

  public SubmissionLoop(
      JobRepository jobs,
      JobCommandService commands,
      JobSubmissionService submission,
      JobCancellationService cancellation,
      @Value("${cae.background.enabled:true}") boolean enabled) {
    this.jobs = jobs;
    this.commands = commands;
    this.submission = submission;
    this.cancellation = cancellation;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${cae.scheduler.scan-ms:1000}")
  public void scan() {
    if (!enabled) return;
    commands.pendingValidation().stream().limit(8).forEach(this::dispatch);
    jobs
        .listByStatus(
            JobStatus.QUEUED,
            JobStatus.WAITING_RESOURCE,
            JobStatus.SUBMITTING,
            JobStatus.CANCEL_REQUESTED)
        .stream()
        .limit(12)
        .forEach(job -> dispatch(SqlRows.id(job)));
  }

  private void dispatch(long id) {
    if (inFlight.size() >= 20 || !inFlight.add(id)) return;
    executor.execute(
        () -> {
          try {
            if ("CANCEL_REQUESTED".equals(jobs.findById(id).get("status")))
              cancellation.continueCancel(id);
            else submission.execute(id);
          } catch (RuntimeException e) {
            LOG.warn(
                "Deferred Job orchestration jobId={} cause={}", id, e.getClass().getSimpleName());
          } finally {
            inFlight.remove(id);
          }
        });
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
