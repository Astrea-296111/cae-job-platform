package com.example.cae.job.submission;

import com.example.cae.job.repository.JobRepository;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class JobSubmissionService {
  private final JobAllocationService allocation;
  private final ExecutionDispatchService dispatch;
  private final JobCommandService commands;
  private final JobRepository jobs;

  public JobSubmissionService(
      JobAllocationService allocation,
      ExecutionDispatchService dispatch,
      JobCommandService commands,
      JobRepository jobs) {
    this.allocation = allocation;
    this.dispatch = dispatch;
    this.commands = commands;
    this.jobs = jobs;
  }

  public void execute(long jobId) {
    if (commands.pendingValidation().contains(jobId)) commands.finishValidation(jobId);
    allocation.allocate(jobId).ifPresent(dispatch::submit);
  }

  public Map<String, Object> current(long jobId) {
    return jobs.findById(jobId);
  }
}
