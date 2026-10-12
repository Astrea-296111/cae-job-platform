package com.example.cae.job.api;

import com.example.cae.job.application.JobApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
  private final JobApplicationService jobs;

  public JobController(JobApplicationService jobs) {
    this.jobs = jobs;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> create(
      Authentication user,
      @Valid @RequestBody CreateJobRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    Map<String, Object> job = jobs.create(user, request, key);
    String number = job.get("jobNo").toString();
    return Map.of(
        "jobNo",
        number,
        "status",
        job.get("status"),
        "links",
        Map.of("self", "/api/v1/jobs/" + number, "events", "/api/v1/jobs/" + number + "/events"));
  }

  @GetMapping("/{jobNo}")
  public Map<String, Object> get(Authentication user, @PathVariable String jobNo) {
    return jobs.get(user, jobNo);
  }

  @GetMapping
  public Map<String, Object> list(
      Authentication user,
      @RequestParam Map<String, Object> parameters,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return jobs.list(user, parameters, page, size);
  }

  @GetMapping("/{jobNo}/events")
  public List<Map<String, Object>> events(Authentication user, @PathVariable String jobNo) {
    return jobs.events(user, jobNo);
  }

  public record Priority(@Min(0) @Max(100) int priority) {}

  @PatchMapping("/{jobNo}/priority")
  public Map<String, Object> priority(
      Authentication user, @PathVariable String jobNo, @Valid @RequestBody Priority request) {
    return jobs.changePriority(user, jobNo, request.priority());
  }
}
