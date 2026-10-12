package com.example.cae.report;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReportController {
  @GetMapping("/jobs/{jobNo}/baselines")
  public List<Map<String, Object>> baselines(
      Authentication user,
      @PathVariable String jobNo,
      @RequestParam(defaultValue = "v1") String parserVersion) {
    return reports.baselines(user, jobNo, parserVersion);
  }

  private final ReportApplicationService reports;

  public ReportController(ReportApplicationService reports) {
    this.reports = reports;
  }

  @GetMapping("/reports")
  public List<Map<String, Object>> list(
      Authentication user, @RequestParam(required = false) Long projectId) {
    return reports.reports(user, projectId, null);
  }

  @GetMapping("/jobs/{jobNo}/reports")
  public List<Map<String, Object>> history(Authentication user, @PathVariable String jobNo) {
    return reports.reports(user, null, jobNo);
  }

  @GetMapping("/jobs/{jobNo}/pipeline")
  public List<Map<String, Object>> stages(Authentication user, @PathVariable String jobNo) {
    return reports.stages(user, jobNo);
  }

  @GetMapping("/reports/{id}")
  public Map<String, Object> detail(Authentication user, @PathVariable long id) {
    return reports.detail(user, id);
  }

  @PostMapping("/reports/{id}/review")
  public Map<String, Object> review(Authentication user, @PathVariable long id) {
    return reports.review(user, id, false);
  }

  @PostMapping("/reports/{id}/approve")
  public Map<String, Object> approve(Authentication user, @PathVariable long id) {
    return reports.review(user, id, true);
  }

  @PostMapping("/jobs/{jobNo}/reports")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> generate(
      Authentication user,
      @PathVariable String jobNo,
      @Valid @RequestBody GenerateRequest request) {
    return reports.generate(
        user,
        jobNo,
        request.parserVersion() == null ? "v1" : request.parserVersion(),
        request.baselineJobNo(),
        request.idempotencyKey() == null ? UUID.randomUUID().toString() : request.idempotencyKey());
  }

  @GetMapping("/jobs/{jobNo}/comparison")
  public Map<String, Object> comparison(
      Authentication user,
      @PathVariable String jobNo,
      @RequestParam(defaultValue = "v1") String parserVersion,
      @RequestParam(required = false) String baselineJobNo) {
    return reports.comparison(user, jobNo, parserVersion, baselineJobNo);
  }

  public record GenerateRequest(
      @Pattern(regexp = "v[12]") String parserVersion,
      @Pattern(regexp = "CAE-[0-9]{8}-[0-9]{6,}") String baselineJobNo,
      @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String idempotencyKey) {}
}
