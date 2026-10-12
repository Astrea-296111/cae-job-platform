package com.example.cae.job.api;

import com.example.cae.job.application.JobLogService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class JobLogController {
  private final JobLogService logs;

  public JobLogController(JobLogService logs) {
    this.logs = logs;
  }

  @GetMapping("/api/v1/jobs/{jobNo}/logs")
  public Map<String, Object> logs(
      Authentication user,
      @PathVariable String jobNo,
      @RequestParam(required = false) Long executionId,
      @RequestParam(defaultValue = "200") int lines) {
    return logs.logs(user, jobNo, executionId, lines);
  }
}
