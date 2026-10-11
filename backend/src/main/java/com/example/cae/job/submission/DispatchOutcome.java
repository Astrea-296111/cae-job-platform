package com.example.cae.job.submission;

/** Transport outcome; an uncertain response never proves that a Solver was not accepted. */
public enum DispatchOutcome {
  ACCEPTED,
  NOOP,
  PERMANENT_FAILURE,
  RETRYABLE_UNCERTAIN
}
