package com.example.cae.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProjectController {
  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  public record CreateProject(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String code,
      @NotBlank @Size(max = 160) String name,
      @Size(max = 1000) String description) {}

  public record CreateModel(
      @NotNull Long projectId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String code,
      @NotBlank @Size(max = 160) String name) {}

  public record CreateRevision(
      @NotNull Long modelId,
      @NotBlank @Size(max = 64) String revision,
      @Size(max = 1000) String description) {}

  @GetMapping("/projects")
  public List<Map<String, Object>> projects(Authentication user) {
    return projects.projects(user);
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> project(
      Authentication user, @Valid @RequestBody CreateProject request) {
    return projects.createProject(user, request.code(), request.name(), request.description());
  }

  @GetMapping("/models")
  public List<Map<String, Object>> models(
      Authentication user, @RequestParam(required = false) Long projectId) {
    return projects.models(user, projectId);
  }

  @PostMapping("/models")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> model(Authentication user, @Valid @RequestBody CreateModel request) {
    return projects.createModel(user, request.projectId(), request.code(), request.name());
  }

  @GetMapping("/model-revisions")
  public List<Map<String, Object>> revisions(
      Authentication user, @RequestParam(required = false) Long projectId) {
    return projects.revisions(user, projectId);
  }

  @PostMapping("/model-revisions")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> revision(
      Authentication user, @Valid @RequestBody CreateRevision request) {
    return projects.createRevision(
        user, request.modelId(), request.revision(), request.description());
  }
}
