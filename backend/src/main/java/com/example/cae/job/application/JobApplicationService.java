package com.example.cae.job.application;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.api.CreateJobRequest;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.project.ProjectService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobApplicationService {
  private final JobRepository jobs;
  private final ProjectAccess access;
  private final ProjectService projects;

  public JobApplicationService(JobRepository jobs, ProjectAccess access, ProjectService projects) {
    this.jobs = jobs;
    this.access = access;
    this.projects = projects;
  }

  @Transactional
  public Map<String, Object> create(Authentication user, CreateJobRequest request) {
    access.require(user, request.projectId());
    projects.requireRevision(request.projectId(), request.modelRevisionId());
    String profile =
        request.solverProfile() == null
            ? request.analysisType().name() + "_STANDARD"
            : request.solverProfile();
    String solver =
        request.solverType() == null
            ? switch (request.analysisType()) {
              case STRUCTURE -> "MOCK_OPTISTRUCT";
              case NVH -> "MOCK_NVH";
              case CRASH -> "MOCK_CRASH";
              case CFD -> "MOCK_CFD";
            }
            : request.solverType();
    String expectedSolver =
        switch (request.analysisType()) {
          case STRUCTURE -> "MOCK_OPTISTRUCT";
          case NVH -> "MOCK_NVH";
          case CRASH -> "MOCK_CRASH";
          case CFD -> "MOCK_CFD";
        };
    if (!profile.equals(request.analysisType().name() + "_STANDARD")
        || !solver.equals(expectedSolver))
      throw ApiException.invalid("Solver profile and solver type must match the analysis type");
    String resourceProfile =
        request.resourceProfile() == null ? "STANDARD" : request.resourceProfile();
    if (!Set.of("SMALL", "STANDARD", "LARGE").contains(resourceProfile))
      throw ApiException.invalid("Unknown resource profile");
    Map<String, Object> resource = resource(request.resource(), resourceProfile);
    Map<String, Object> input = request.input() == null ? Map.of() : request.input();
    Object manifest = input.get("manifest");
    if (manifest != null && !(manifest instanceof String))
      throw ApiException.invalid("Input manifest must be a relative path string");
    Object entryDeck = input.get("entryDeck");
    if (entryDeck != null && !(entryDeck instanceof String))
      throw ApiException.invalid("Input entryDeck must be a relative path string");
    String casePath = request.casePath();
    if (casePath == null && manifest instanceof String path) {
      path = path.replace('\\', '/');
      if (path.startsWith("demo-data/")) path = path.substring("demo-data/".length());
      int slash = path.lastIndexOf('/');
      if (slash >= 0) casePath = path.substring(0, slash);
    }
    if (casePath != null
        && (casePath.startsWith("/")
            || casePath.startsWith("\\")
            || casePath.contains("..")
            || casePath.indexOf('\u0000') >= 0
            || casePath.contains(":")))
      throw ApiException.invalid("Case path must be a relative path inside the demo case root");
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("projectId", request.projectId());
    fields.put("modelRevisionId", request.modelRevisionId());
    fields.put("analysisType", request.analysisType().name());
    fields.put("conditionCode", request.conditionCode());
    fields.put("solverType", solver);
    fields.put("solverProfile", profile);
    fields.put("priority", request.priority() == null ? 50 : request.priority());
    fields.put("submitterId", user.getName());
    fields.put("casePath", casePath);
    fields.put("inputManifestUri", manifest);
    fields.put("input", input);
    fields.put("resource", resource);
    fields.put("resourceProfileId", resourceProfile);
    fields.put("mock", request.mock() == null ? Map.of() : request.mock());
    Map<String, Object> created = jobs.create(fields);
    jobs.audit(
        user.getName(),
        "JOB_CREATE",
        "JOB",
        created.get("jobNo").toString(),
        null,
        Map.of("status", created.get("status"), "projectId", created.get("projectId")));
    return created;
  }

  private Map<String, Object> resource(Map<String, Object> requested, String profile) {
    int cpu =
        switch (profile) {
          case "SMALL" -> 4;
          case "LARGE" -> 16;
          default -> 8;
        };
    Map<String, Object> result =
        new LinkedHashMap<>(Map.of("cpu", cpu, "memoryGb", cpu * 2, "walltimeMin", 30));
    if (requested != null) {
      for (String field : requested.keySet())
        if (!Set.of("cpu", "memoryGb", "walltimeMin").contains(field))
          throw ApiException.invalid("Unsupported resource field: " + field);
      result.putAll(requested);
    }
    range(result, "cpu", 1, 32);
    range(result, "memoryGb", 1, 64);
    range(result, "walltimeMin", 1, 120);
    return result;
  }

  private void range(Map<String, Object> value, String key, int min, int max) {
    Object field = value.get(key);
    if (!(field instanceof Number number)
        || number.doubleValue() != number.intValue()
        || number.intValue() < min
        || number.intValue() > max)
      throw ApiException.invalid(
          "Resource " + key + " must be an integer between " + min + " and " + max);
  }

  public Map<String, Object> get(Authentication user, String jobNo) {
    Map<String, Object> job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    return job;
  }

  public List<Map<String, Object>> events(Authentication user, String jobNo) {
    return jobs.events(SqlRows.id(get(user, jobNo)));
  }

  public Map<String, Object> list(
      Authentication user, Map<String, Object> requested, int page, int size) {
    if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
      throw ApiException.invalid("Page must be >= 0 and size must be between 1 and 100");
    Map<String, Object> filters = new LinkedHashMap<>(requested);
    if (!access.admin(user)) filters.put("username", user.getName());
    Object project = filters.get("projectId");
    if (project != null) {
      try {
        access.require(user, Long.parseLong(project.toString()));
      } catch (NumberFormatException e) {
        throw ApiException.invalid("Project id must be a positive integer");
      }
    }
    Object from = filters.get("dateFrom");
    if (from != null) filters.put("createdFrom", date(from.toString()));
    Object to = filters.get("dateTo");
    if (to != null) filters.put("createdTo", date(to.toString()).plusDays(1));
    return Map.of(
        "items",
        jobs.list(filters, page * size, size),
        "total",
        jobs.count(filters),
        "page",
        page,
        "size",
        size);
  }

  private LocalDate date(String value) {
    try {
      return LocalDate.parse(value);
    } catch (java.time.format.DateTimeParseException e) {
      throw ApiException.invalid("Date filters must use yyyy-MM-dd");
    }
  }

  @Transactional
  public Map<String, Object> changePriority(Authentication user, String jobNo, int priority) {
    Map<String, Object> job = get(user, jobNo);
    access.requireLead(user, ((Number) job.get("projectId")).longValue());
    if (priority < 0 || priority > 100)
      throw ApiException.invalid("Priority must be between 0 and 100");
    Map<String, Object> result = jobs.patch(SqlRows.id(job), Map.of("priority", priority));
    jobs.audit(
        user.getName(),
        "PRIORITY_CHANGE",
        "JOB",
        jobNo,
        Map.of("priority", job.get("priority")),
        Map.of("priority", priority));
    return result;
  }
}
