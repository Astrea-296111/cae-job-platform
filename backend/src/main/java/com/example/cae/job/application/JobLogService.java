package com.example.cae.job.application;

import com.example.cae.artifact.ArtifactFileStore;
import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.job.execution.ExecutionRepository;
import com.example.cae.job.repository.JobRepository;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;

@org.springframework.stereotype.Service
public class JobLogService {
  private final JobRepository jobs;
  private final ExecutionRepository executions;
  private final ProjectAccess access;
  private final ArtifactFileStore files;
  private final String token;

  public JobLogService(
      JobRepository jobs,
      ExecutionRepository executions,
      ProjectAccess access,
      ArtifactFileStore files,
      @Value("${SERVICE_TOKEN:demo-service-token}") String token) {
    this.jobs = jobs;
    this.executions = executions;
    this.access = access;
    this.files = files;
    this.token = token;
  }

  public Map<String, Object> logs(Authentication user, String jobNo, Long executionId, int lines) {
    var job = jobs.findByNo(jobNo);
    access.requireJob(user, job);
    if (lines < 1 || lines > 500) throw ApiException.invalid("Log tail supports 1–500 lines");
    if (executionId == null && job.get("currentExecutionId") == null)
      return Map.of("jobNo", jobNo, "lines", List.of(), "truncated", false);
    long id =
        executionId == null ? ((Number) job.get("currentExecutionId")).longValue() : executionId;
    var execution = executions.find(id);
    if (((Number) execution.get("jobId")).longValue() != ((Number) job.get("id")).longValue())
      throw ApiException.notFound("Execution for this Job");
    String request = execution.get("submitRequestId").toString();
    if (!request.matches("[A-Za-z0-9_-]{1,100}"))
      throw ApiException.invalid("Unsafe execution identity");
    try {
      Path path =
          files.requireJob("jobs/" + jobNo + "/executions/" + request + "/solver.log", jobNo);
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        return Map.of("jobNo", jobNo, "executionId", id, "lines", List.of(), "truncated", false);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw ApiException.conflict("Log must be a regular file");
      try (var channel =
          Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        long size = channel.size();
        long offset = Math.max(0, size - 256 * 1024);
        channel.position(offset);
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size - offset, 256 * 1024));
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
        buffer.flip();
        String text = StandardCharsets.UTF_8.decode(buffer).toString();
        if (offset > 0) {
          int newline = text.indexOf('\n');
          text = newline < 0 ? "" : text.substring(newline + 1);
        }
        text =
            text.replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        if (!token.isBlank()) text = text.replace(token, "[REDACTED]");
        text =
            text.replaceAll("(?i)(authorization\\s*[=:]\\s*)[^\\n]+", "$1[REDACTED]")
                .replaceAll(
                    "(?i)((?:password|service[_-]?token)\\s*[=:]\\s*)[^\\s,;]+", "$1[REDACTED]");
        List<String> all = text.lines().toList();
        return Map.of(
            "jobNo",
            jobNo,
            "executionId",
            id,
            "lines",
            all.subList(Math.max(0, all.size() - lines), all.size()),
            "truncated",
            offset > 0 || all.size() > lines,
            "updatedAt",
            Files.getLastModifiedTime(path).toInstant().toString());
      }
    } catch (IllegalArgumentException unsafe) {
      throw ApiException.conflict("Solver log path is unsafe");
    } catch (IOException unavailable) {
      throw ApiException.conflict("Solver log is temporarily unavailable");
    }
  }
}
