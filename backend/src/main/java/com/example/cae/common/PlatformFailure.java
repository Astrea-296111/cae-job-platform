package com.example.cae.common;

import java.util.Map;

public class PlatformFailure extends RuntimeException {
  private final FailureCode code;
  private final String stage;
  private final Map<String, Object> details;

  public PlatformFailure(
      FailureCode code, String stage, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.stage = stage;
    this.details = details;
  }

  public FailureCode code() {
    return code;
  }

  public String stage() {
    return stage;
  }

  public Map<String, Object> details() {
    return details;
  }

  public Map<String, Object> toMap() {
    return Map.of(
        "code",
        code.name(),
        "stage",
        stage,
        "message",
        getMessage(),
        "retryable",
        code.retryable(),
        "details",
        details);
  }
}
