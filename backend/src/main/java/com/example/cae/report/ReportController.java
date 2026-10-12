package com.example.cae.report;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.result.ResultRepository;
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
  private final ReportApplicationService reports;
  private final JobRepository jobs;
  private final ProjectAccess access;
  private final ResultRepository results;
  private final PipelineRepository repository;
  private final BaselineComparisonService comparisons;

  public ReportController(
      ReportApplicationService reports,
      JobRepository jobs,
      ProjectAccess access,
      ResultRepository results,
      PipelineRepository repository,
      BaselineComparisonService comparisons) {
    this.reports = reports;
    this.jobs = jobs;
    this.access = access;
    this.results = results;
    this.repository = repository;
    this.comparisons = comparisons;
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
    var job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    if (baselineJobNo != null) access.requireJob(user, jobs.findByNo(baselineJobNo));
    var parse =
        results
            .find(SqlRows.id(job), parserVersion)
            .orElseThrow(() -> com.example.cae.common.ApiException.notFound("Parser version"));
    var base = repository.baseline(job, parse, baselineJobNo);
    if (baselineJobNo != null && base.isEmpty())
      throw com.example.cae.common.ApiException.invalid("Baseline is incompatible");
    var response = new java.util.LinkedHashMap<String, Object>();
    response.put(
        "baseline",
        base.isEmpty()
            ? null
            : Map.of("jobNo", base.get().get("jobNo"), "parseRecordId", SqlRows.id(base.get())));
    response.put(
        "comparisons",
        comparisons.compare(
            repository.metrics(SqlRows.id(parse)),
            base.isEmpty() ? List.of() : repository.metrics(SqlRows.id(base.get()))));
    return response;
  }

  public record GenerateRequest(
      @Pattern(regexp = "v[12]") String parserVersion,
      @Pattern(regexp = "CAE-[0-9]{8}-[0-9]{6,}") String baselineJobNo,
      @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String idempotencyKey) {}
}
