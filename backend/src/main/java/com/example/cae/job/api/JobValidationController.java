package com.example.cae.job.api;

import com.example.cae.job.application.JobApplicationService;
import com.example.cae.job.validation.CaseValidationService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobValidationController {
  private final JobApplicationService jobs;
  private final CaseValidationService validation;

  public JobValidationController(JobApplicationService jobs, CaseValidationService validation) {
    this.jobs = jobs;
    this.validation = validation;
  }

  @PostMapping("/{jobNo}/validate")
  public Map<String, Object> validate(Authentication user, @PathVariable String jobNo) {
    return validation.validate(jobs.get(user, jobNo));
  }
}
