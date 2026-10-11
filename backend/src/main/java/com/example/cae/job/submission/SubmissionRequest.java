package com.example.cae.job.submission;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record SubmissionRequest(
    @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String idempotencyKey,
    Map<String, Object> mock) {
  public static SubmissionRequest empty() {
    return new SubmissionRequest(null, null);
  }
}
