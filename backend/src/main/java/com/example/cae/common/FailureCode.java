package com.example.cae.common;

/** Stable platform failure codes, independent of raw solver stderr or exit code. */
public enum FailureCode {
  INPUT_FILE_MISSING(false),
  INPUT_INCLUDE_MISSING(false),
  INPUT_INVALID(false),
  NO_RESOURCE(true),
  NODE_OFFLINE(true),
  AGENT_TIMEOUT(true),
  SUBMIT_REJECTED(false),
  SOLVER_RUNTIME_FAILED(false),
  SOLVER_TIMEOUT(true),
  POST_PROCESS_FAILED(true),
  REPORT_GENERATION_FAILED(true),
  CANCEL_FAILED(true),
  UNKNOWN_REMOTE_STATE(true);
  private final boolean retryable;

  FailureCode(boolean retryable) {
    this.retryable = retryable;
  }

  public boolean retryable() {
    return retryable;
  }
}
