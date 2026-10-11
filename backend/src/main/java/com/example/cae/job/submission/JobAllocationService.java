package com.example.cae.job.submission;

import com.example.cae.common.PlatformFailure;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.job.validation.InputSnapshotService;
import com.example.cae.resource.ResourceReservationService;
import com.example.cae.scheduler.SchedulerService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobAllocationService {
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final InputSnapshotService snapshots;
  private final SchedulerService scheduler;
  private final ResourceReservationService resources;

  public JobAllocationService(
      JobRepository jobs,
      ExecutionRepository executions,
      InputSnapshotService snapshots,
      SchedulerService scheduler,
      ResourceReservationService resources) {
    this.jobs = jobs;
    this.executions = executions;
    this.snapshots = snapshots;
    this.scheduler = scheduler;
    this.resources = resources;
  }

  /** Job, node/license reservation and attempt association share one short transaction. */
  @Transactional
  public Optional<Long> allocate(long jobId) {
    Map<String, Object> job = jobs.lockById(jobId);
    String status = job.get("status").toString();
    if (status.equals("SUBMITTING"))
      return Optional.of(((Number) job.get("currentExecutionId")).longValue());
    if (!status.equals("QUEUED") && !status.equals("WAITING_RESOURCE")) return Optional.empty();
    try {
      snapshots.verifySnapshot(job);
    } catch (PlatformFailure failure) {
      if (status.equals("WAITING_RESOURCE"))
        job = jobs.transition(job, JobStatus.QUEUED, "JOB_QUEUED", Map.of());
      job = jobs.transition(job, JobStatus.SUBMITTING, "SUBMISSION_PRECHECK", Map.of());
      job =
          jobs.patch(
              job,
              Map.of("failureCode", failure.code().name(), "failureMessage", failure.getMessage()));
      jobs.transition(job, JobStatus.SUBMIT_FAILED, "SUBMISSION_INPUT_FAILED", failure.toMap());
      return Optional.empty();
    }
    Map<String, Object> mock = MockExecutionOptions.validate((Map<String, Object>) job.get("mock"));
    var reservation = scheduler.allocate(job);
    if (reservation.isEmpty()) {
      if (status.equals("QUEUED")) {
        job =
            jobs.patch(
                job,
                Map.of(
                    "failureCode",
                    "NO_RESOURCE",
                    "failureMessage",
                    "Compatible node or license capacity is currently unavailable"));
        jobs.transition(
            job, JobStatus.WAITING_RESOURCE, "WAITING_RESOURCE", Map.of("retryable", true));
      }
      return Optional.empty();
    }
    if (status.equals("WAITING_RESOURCE"))
      job = jobs.transition(job, JobStatus.QUEUED, "JOB_QUEUED", Map.of("resourceAvailable", true));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("executionRequestId", "exec-" + UUID.randomUUID());
    request.put("jobNo", job.get("jobNo"));
    request.put("workspace", job.get("workspace"));
    request.put("solverProfile", job.get("solverProfile"));
    request.put("analysisType", job.get("analysisType"));
    request.put("conditionCode", job.get("conditionCode"));
    request.put("snapshotChecksum", job.get("inputChecksum"));
    request.put("resource", job.get("resource"));
    request.put("mock", mock);
    var assigned = reservation.get();
    Map<String, Object> execution =
        executions.create(jobId, assigned.nodeId(), assigned.id(), request);
    long executionId = SqlRows.id(execution);
    resources.associateExecution(assigned.id(), executionId);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("currentExecutionId", executionId);
    fields.put("failureCode", null);
    fields.put("failureMessage", null);
    job = jobs.patch(job, fields);
    jobs.transition(
        job,
        JobStatus.SUBMITTING,
        "EXECUTION_ALLOCATED",
        Map.of(
            "executionId",
            executionId,
            "executionRequestId",
            request.get("executionRequestId"),
            "nodeId",
            assigned.nodeId(),
            "reservationId",
            assigned.id()));
    return Optional.of(executionId);
  }
}
