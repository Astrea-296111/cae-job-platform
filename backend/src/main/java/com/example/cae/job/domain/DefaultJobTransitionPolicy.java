package com.example.cae.job.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DefaultJobTransitionPolicy implements JobTransitionPolicy {
  private static final Map<JobStatus, Set<JobStatus>> EDGES = new EnumMap<>(JobStatus.class);

  static {
    allow(JobStatus.CREATED, JobStatus.VALIDATING, JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.VALIDATING,
        JobStatus.READY,
        JobStatus.VALIDATION_FAILED,
        JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.READY, JobStatus.WAITING_RESOURCE, JobStatus.QUEUED, JobStatus.CANCEL_REQUESTED);
    allow(JobStatus.WAITING_RESOURCE, JobStatus.QUEUED, JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.QUEUED,
        JobStatus.SUBMITTING,
        JobStatus.WAITING_RESOURCE,
        JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.SUBMITTING,
        JobStatus.SUBMITTED,
        JobStatus.SUBMIT_FAILED,
        JobStatus.UNKNOWN,
        JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.SUBMITTED,
        JobStatus.RUNNING,
        JobStatus.POST_PROCESSING,
        JobStatus.RUNTIME_FAILED,
        JobStatus.UNKNOWN,
        JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.RUNNING,
        JobStatus.POST_PROCESSING,
        JobStatus.RUNTIME_FAILED,
        JobStatus.UNKNOWN,
        JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.POST_PROCESSING,
        JobStatus.REPORTING,
        JobStatus.POST_PROCESS_FAILED,
        JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.REPORTING,
        JobStatus.SUCCESS,
        JobStatus.REPORT_FAILED,
        JobStatus.CANCEL_REQUESTED);
    allow(
        JobStatus.CANCEL_REQUESTED,
        JobStatus.CANCELLED,
        JobStatus.UNKNOWN,
        JobStatus.POST_PROCESSING,
        JobStatus.RUNTIME_FAILED);
    allow(
        JobStatus.UNKNOWN,
        JobStatus.SUBMITTED,
        JobStatus.RUNNING,
        JobStatus.POST_PROCESSING,
        JobStatus.RUNTIME_FAILED,
        JobStatus.SUBMIT_FAILED,
        JobStatus.CANCEL_REQUESTED,
        JobStatus.CANCELLED);
    // Retry edges are explicit; even a failed or cancelled job can never jump to RUNNING.
    allow(JobStatus.VALIDATION_FAILED, JobStatus.VALIDATING);
    allow(JobStatus.SUBMIT_FAILED, JobStatus.READY);
    allow(JobStatus.RUNTIME_FAILED, JobStatus.READY);
    allow(JobStatus.POST_PROCESS_FAILED, JobStatus.POST_PROCESSING);
    allow(JobStatus.REPORT_FAILED, JobStatus.REPORTING, JobStatus.POST_PROCESSING);
    allow(JobStatus.CANCELLED, JobStatus.READY, JobStatus.VALIDATING);
  }

  private static void allow(JobStatus from, JobStatus... to) {
    EDGES.put(from, Set.of(to));
  }

  @Override
  public boolean canTransit(JobStatus from, JobStatus to) {
    return EDGES.getOrDefault(from, Set.of()).contains(to);
  }
}
