package com.example.cae.artifact;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ArtifactController {
  private final ArtifactRepository artifacts;
  private final ArtifactFileStore files;
  private final JobRepository jobs;
  private final ProjectAccess access;

  public ArtifactController(
      ArtifactRepository artifacts,
      ArtifactFileStore files,
      JobRepository jobs,
      ProjectAccess access) {
    this.artifacts = artifacts;
    this.files = files;
    this.jobs = jobs;
    this.access = access;
  }

  @GetMapping("/api/v1/jobs/{jobNo}/artifacts")
  public List<Map<String, Object>> list(Authentication user, @PathVariable String jobNo) {
    Map<String, Object> job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    return artifacts.list(SqlRows.id(job));
  }

  @GetMapping("/api/v1/jobs/{jobNo}/artifacts/{artifactId}/download")
  public ResponseEntity<InputStreamResource> downloadForJob(
      Authentication user, @PathVariable String jobNo, @PathVariable long artifactId) {
    Map<String, Object> job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    Map<String, Object> artifact = artifacts.find(artifactId);
    if (((Number) artifact.get("jobId")).longValue() != SqlRows.id(job))
      throw ApiException.notFound("Artifact");
    return stream(artifact, job);
  }

  @GetMapping("/api/v1/artifacts/{artifactId}/download")
  public ResponseEntity<InputStreamResource> download(
      Authentication user, @PathVariable long artifactId) {
    Map<String, Object> artifact = artifacts.find(artifactId);
    Map<String, Object> job = jobs.findById(((Number) artifact.get("jobId")).longValue());
    access.requireJob(user, job);
    return stream(artifact, job);
  }

  private ResponseEntity<InputStreamResource> stream(
      Map<String, Object> artifact, Map<String, Object> job) {
    try {
      files.requireJob(artifact.get("uri").toString(), job.get("jobNo").toString());
      MediaType type = MediaType.parseMediaType(artifact.get("contentType").toString());
      long size = ((Number) artifact.get("sizeBytes")).longValue();
      String disposition =
          ContentDisposition.attachment()
              .filename(artifact.get("fileName").toString(), StandardCharsets.UTF_8)
              .build()
              .toString();
      var response =
          ResponseEntity.ok()
              .contentType(type)
              .contentLength(size)
              .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
              .header("X-Content-Type-Options", "nosniff");
      return response.body(new InputStreamResource(files.openVerified(artifact)));
    } catch (IllegalArgumentException invalid) {
      throw ApiException.conflict("Artifact is unavailable or its integrity check failed");
    }
  }
}
