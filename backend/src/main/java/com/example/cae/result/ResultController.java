package com.example.cae.result;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs/{jobNo}")
public class ResultController {
  private final JobRepository jobs;
  private final ProjectAccess access;
  private final ResultRepository results;
  private final ResultParsingService parsing;

  public ResultController(
      JobRepository jobs,
      ProjectAccess access,
      ResultRepository results,
      ResultParsingService parsing) {
    this.jobs = jobs;
    this.access = access;
    this.results = results;
    this.parsing = parsing;
  }

  @GetMapping("/metrics")
  public List<Map<String, Object>> metrics(
      Authentication user,
      @PathVariable String jobNo,
      @RequestParam(required = false) String parserVersion) {
    Map<String, Object> job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    if (parserVersion != null)
      try {
        ResultParserRegistry.validateVersion(parserVersion);
      } catch (IllegalArgumentException invalid) {
        throw ApiException.invalid(invalid.getMessage());
      }
    return results.metrics(SqlRows.id(job), parserVersion);
  }

  @GetMapping("/parses")
  public List<Map<String, Object>> history(Authentication user, @PathVariable String jobNo) {
    Map<String, Object> job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    return results.history(SqlRows.id(job));
  }

  @PostMapping("/reparse")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> reparse(
      Authentication user, @PathVariable String jobNo, @Valid @RequestBody ReparseRequest request) {
    return parsing.reparse(user, jobNo, request.parserVersion());
  }

  public record ReparseRequest(@NotBlank @Pattern(regexp = "v[12]") String parserVersion) {}
}
