package com.example.cae.job.domain;

import com.example.cae.common.ApiException;
import java.util.Map;

/** Domain state is the only authority deciding legal business transitions. */
public class CaeJob {
  private final long id;
  private final String jobNo;
  private final long version;
  private JobStatus status;
  private final JobTransitionPolicy policy;

  public CaeJob(long id, String jobNo, JobStatus status, long version, JobTransitionPolicy policy) {
    this.id = id;
    this.jobNo = jobNo;
    this.status = status;
    this.version = version;
    this.policy = policy;
  }

  public static CaeJob from(Map<String, Object> row, JobTransitionPolicy policy) {
    return new CaeJob(
        ((Number) row.get("id")).longValue(),
        row.get("jobNo").toString(),
        JobStatus.valueOf(row.get("status").toString()),
        ((Number) row.get("version")).longValue(),
        policy);
  }

  /** Duplicate delivery of the current status is idempotent and does not append an event. */
  public boolean transitTo(JobStatus target) {
    if (status == target) return false;
    if (!policy.canTransit(status, target))
      throw ApiException.conflict("Job cannot transit from " + status + " to " + target);
    status = target;
    return true;
  }

  public long id() {
    return id;
  }

  public String jobNo() {
    return jobNo;
  }

  public JobStatus status() {
    return status;
  }

  public long version() {
    return version;
  }
}
