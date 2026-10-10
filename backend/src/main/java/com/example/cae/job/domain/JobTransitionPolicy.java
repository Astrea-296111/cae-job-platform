package com.example.cae.job.domain;

public interface JobTransitionPolicy {
  boolean canTransit(JobStatus from, JobStatus to);
}
