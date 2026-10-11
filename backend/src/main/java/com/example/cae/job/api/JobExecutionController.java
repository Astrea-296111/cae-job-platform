package com.example.cae.job.api;

import com.example.cae.common.SqlRows;
import com.example.cae.job.application.JobApplicationService;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.submission.JobCancellationService;
import com.example.cae.job.submission.JobCommandService;
import com.example.cae.job.submission.SubmissionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs/{jobNo}")
public class JobExecutionController {
  private final JobCommandService commands;
  private final JobCancellationService cancellation;
  private final ExecutionRepository executions;
  private final JobApplicationService jobs;

  public JobExecutionController(
      JobCommandService commands,
      JobCancellationService cancellation,
      ExecutionRepository executions,
      JobApplicationService jobs) {
    this.commands = commands;
    this.cancellation = cancellation;
    this.executions = executions;
    this.jobs = jobs;
  }

  @PostMapping("/submit")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> submit(
      Authentication user,
      @PathVariable String jobNo,
      @Valid @RequestBody(required = false) SubmissionRequest request) {
    return commands.submit(user, jobNo, request);
  }

  @PostMapping("/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> retry(
      Authentication user,
      @PathVariable String jobNo,
      @Valid @RequestBody(required = false) SubmissionRequest request) {
    return commands.retry(user, jobNo, request);
  }

  @PostMapping("/cancel")
  public Map<String, Object> cancel(Authentication user, @PathVariable String jobNo) {
    return cancellation.cancel(user, jobNo);
  }

  @GetMapping("/executions")
  public List<Map<String, Object>> history(Authentication user, @PathVariable String jobNo) {
    return executions.history(SqlRows.id(jobs.get(user, jobNo))).stream()
        .map(
            execution -> {
              var safe = new java.util.LinkedHashMap<>(execution);
              safe.remove("request");
              return (Map<String, Object>) safe;
            })
        .toList();
  }
}
