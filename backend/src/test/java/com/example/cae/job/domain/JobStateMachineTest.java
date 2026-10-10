package com.example.cae.job.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.example.cae.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class JobStateMachineTest {
  private final JobTransitionPolicy policy = new DefaultJobTransitionPolicy();

  @ParameterizedTest
  @CsvSource({
    "CREATED, READY",
    "VALIDATING, QUEUED",
    "READY, RUNNING",
    "WAITING_RESOURCE, SUBMITTING",
    "QUEUED, RUNNING",
    "SUBMITTING, POST_PROCESSING",
    "RUNNING, SUCCESS",
    "POST_PROCESSING, SUCCESS"
  })
  void cannotSkipRequiredValidationAdmissionOrReporting(JobStatus from, JobStatus to) {
    CaeJob job = job(from);
    ApiException conflict = assertThrows(ApiException.class, () -> job.transitTo(to));
    assertEquals(HttpStatus.CONFLICT, conflict.status());
    assertEquals("JOB_STATE_CONFLICT", conflict.code());
    assertEquals(from, job.status(), "rejected transition must leave domain state untouched");
  }

  @ParameterizedTest
  @CsvSource({
    "VALIDATING, VALIDATION_FAILED, VALIDATING",
    "SUBMITTING, SUBMIT_FAILED, READY",
    "RUNNING, RUNTIME_FAILED, READY",
    "POST_PROCESSING, POST_PROCESS_FAILED, POST_PROCESSING",
    "REPORTING, REPORT_FAILED, REPORTING"
  })
  void stageFailureIsTerminalAndExplicitRetryRestartsItsCorrectStage(
      JobStatus stage, JobStatus failure, JobStatus retryEntry) {
    CaeJob job = job(stage);
    assertTrue(job.transitTo(failure));
    assertTrue(job.status().terminal());
    assertFalse(job.transitTo(failure), "duplicate failure notification must be idempotent");
    assertTrue(job.transitTo(retryEntry));
    assertFalse(job.status().terminal());
  }

  @Test
  void completeLifecycleCanWaitForCapacityAndThenFinish() {
    CaeJob job = job(JobStatus.CREATED);
    advance(
        job,
        JobStatus.VALIDATING,
        JobStatus.READY,
        JobStatus.WAITING_RESOURCE,
        JobStatus.QUEUED,
        JobStatus.SUBMITTING,
        JobStatus.SUBMITTED,
        JobStatus.RUNNING,
        JobStatus.POST_PROCESSING,
        JobStatus.REPORTING,
        JobStatus.SUCCESS);
    assertTrue(job.status().terminal());
    assertFalse(job.transitTo(JobStatus.SUCCESS));
  }

  @Test
  void pollingCanDiscoverCompletedSolverBeforeObservingRunning() {
    CaeJob job = job(JobStatus.SUBMITTED);
    advance(job, JobStatus.POST_PROCESSING, JobStatus.REPORTING, JobStatus.SUCCESS);
  }

  @ParameterizedTest
  @EnumSource(
      value = JobStatus.class,
      names = {
        "SUCCESS",
        "VALIDATION_FAILED",
        "SUBMIT_FAILED",
        "RUNTIME_FAILED",
        "POST_PROCESS_FAILED",
        "REPORT_FAILED",
        "CANCELLED"
      })
  void terminalJobsCannotJumpToRunning(JobStatus terminal) {
    CaeJob job = job(terminal);
    assertTrue(terminal.terminal());
    assertThrows(ApiException.class, () -> job.transitTo(JobStatus.RUNNING));
    assertEquals(terminal, job.status());
  }

  @Test
  void staleSubmittedAndRunningEventsDoNotUndoCancelIntent() {
    CaeJob submitting = job(JobStatus.SUBMITTING);
    assertTrue(submitting.transitTo(JobStatus.CANCEL_REQUESTED));
    assertThrows(ApiException.class, () -> submitting.transitTo(JobStatus.SUBMITTED));
    assertThrows(ApiException.class, () -> submitting.transitTo(JobStatus.RUNNING));
    assertFalse(submitting.transitTo(JobStatus.CANCEL_REQUESTED));
    assertTrue(submitting.transitTo(JobStatus.CANCELLED));
    assertFalse(submitting.transitTo(JobStatus.CANCELLED));
  }

  @Test
  void runningCancelRequiresRequestBeforeRemoteConfirmation() {
    CaeJob job = job(JobStatus.RUNNING);
    assertThrows(ApiException.class, () -> job.transitTo(JobStatus.CANCELLED));
    advance(job, JobStatus.CANCEL_REQUESTED, JobStatus.CANCELLED);
  }

  @Test
  void lateRuntimeOrPostEventsCannotRegressReportOrSuccess() {
    CaeJob job = job(JobStatus.POST_PROCESSING);
    assertThrows(ApiException.class, () -> job.transitTo(JobStatus.RUNNING));
    advance(job, JobStatus.REPORTING, JobStatus.SUCCESS);
    assertThrows(ApiException.class, () -> job.transitTo(JobStatus.POST_PROCESSING));
    assertThrows(ApiException.class, () -> job.transitTo(JobStatus.CANCEL_REQUESTED));
    assertEquals(JobStatus.SUCCESS, job.status());
  }

  @Test
  void temporaryRemoteLossCanRecoverWithoutCreatingANewAttempt() {
    CaeJob job = job(JobStatus.RUNNING);
    advance(
        job,
        JobStatus.UNKNOWN,
        JobStatus.RUNNING,
        JobStatus.UNKNOWN,
        JobStatus.POST_PROCESSING,
        JobStatus.REPORTING,
        JobStatus.SUCCESS);
    assertEquals(7L, job.id());
    assertEquals("CAE-20261010-000007", job.jobNo());
  }

  @Test
  void retriesReturnOnlyToTheirExplicitStageEntry() {
    assertTrue(job(JobStatus.VALIDATION_FAILED).transitTo(JobStatus.VALIDATING));
    assertTrue(job(JobStatus.SUBMIT_FAILED).transitTo(JobStatus.READY));
    assertTrue(job(JobStatus.RUNTIME_FAILED).transitTo(JobStatus.READY));
    assertTrue(job(JobStatus.POST_PROCESS_FAILED).transitTo(JobStatus.POST_PROCESSING));
    assertTrue(job(JobStatus.REPORT_FAILED).transitTo(JobStatus.REPORTING));
    assertTrue(job(JobStatus.CANCELLED).transitTo(JobStatus.READY));
  }

  private CaeJob job(JobStatus status) {
    return new CaeJob(7, "CAE-20261010-000007", status, 3, policy);
  }

  private void advance(CaeJob job, JobStatus... targets) {
    for (JobStatus target : targets) {
      assertTrue(job.transitTo(target));
      assertEquals(target, job.status());
    }
  }
}
