package com.example.cae.agent;

import com.example.cae.common.FailureCode;

/** Transport failure does not prove whether the remote process was accepted. */
public final class AgentException extends RuntimeException {
  private final FailureCode code;
  private final int httpStatus;
  private final boolean ambiguous;

  public AgentException(FailureCode code, String message, int httpStatus, boolean ambiguous) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
    this.ambiguous = ambiguous;
  }

  public FailureCode code() {
    return code;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public boolean ambiguous() {
    return ambiguous;
  }

  public boolean notFound() {
    return httpStatus == 404;
  }
}
