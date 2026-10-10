package com.example.cae.job.domain;

import java.util.Set;

public enum JobStatus {
  CREATED,
  VALIDATING,
  READY,
  WAITING_RESOURCE,
  QUEUED,
  SUBMITTING,
  SUBMITTED,
  RUNNING,
  POST_PROCESSING,
  REPORTING,
  SUCCESS,
  VALIDATION_FAILED,
  SUBMIT_FAILED,
  RUNTIME_FAILED,
  POST_PROCESS_FAILED,
  REPORT_FAILED,
  CANCEL_REQUESTED,
  CANCELLED,
  UNKNOWN;
  private static final Set<JobStatus> TERMINAL =
      Set.of(
          SUCCESS,
          VALIDATION_FAILED,
          SUBMIT_FAILED,
          RUNTIME_FAILED,
          POST_PROCESS_FAILED,
          REPORT_FAILED,
          CANCELLED);

  public boolean terminal() {
    return TERMINAL.contains(this);
  }
}
