package com.example.cae.batch;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.*;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.report.RenderFingerprint;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatchService {
  private final BatchRepository batches;
  private final ProjectAccess access;
  private final JobRepository jobs;
  private final JsonCodec json;
  private final RenderFingerprint fingerprints;
  private final BackpressureService pressure;

  public BatchService(
      BatchRepository batches,
      ProjectAccess access,
      JobRepository jobs,
      JsonCodec json,
      RenderFingerprint fingerprints,
      BackpressureService pressure) {
    this.batches = batches;
    this.access = access;
    this.jobs = jobs;
    this.json = json;
    this.fingerprints = fingerprints;
    this.pressure = pressure;
  }

  @Transactional
  public Map<String, Object> create(Authentication user, BatchRequest request) {
    access.require(user, request.projectId());
    if (request.jobs().stream().anyMatch(job -> !request.projectId().equals(job.projectId())))
      throw ApiException.invalid("Every child must belong to the batch project");
    String encoded = json.write(request);
    if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
      throw ApiException.invalid("Batch intent exceeds 2 MiB");
    var canonical = new LinkedHashMap<>(json.readMap(encoded));
    canonical.remove("idempotencyKey");
    var batch =
        batches.claim(
            user.getName(),
            request.idempotencyKey(),
            fingerprints.of(canonical),
            request.name(),
            request.projectId(),
            request.jobs().size());
    if (Boolean.TRUE.equals(batch.remove("newIntent"))) {
      batches.items(SqlRows.id(batch), request.jobs());
      jobs.audit(
          user.getName(),
          "BATCH_CREATE",
          "BATCH",
          batch.get("batchNo").toString(),
          null,
          Map.of("total", request.jobs().size()));
    }
    return view(batch);
  }

  public Map<String, Object> detail(Authentication user, String no) {
    var batch = batches.find(no);
    access.require(user, ((Number) batch.get("projectId")).longValue());
    return view(batch);
  }

  public List<Map<String, Object>> list(Authentication user, Long project) {
    if (project != null) access.require(user, project);
    return batches.list(user.getName(), access.admin(user), project).stream()
        .map(this::view)
        .toList();
  }

  private Map<String, Object> view(Map<String, Object> batch) {
    var children = batches.children(SqlRows.id(batch));
    long waiting = 0, running = 0, success = 0, failed = 0, cancelled = 0;
    for (var child : children) {
      String admission = child.get("admissionStatus").toString();
      String state = Objects.toString(child.get("status"), "PENDING");
      if (admission.equals("REJECTED") || state.endsWith("FAILED")) failed++;
      else if (state.equals("SUCCESS")) success++;
      else if (state.equals("CANCELLED")) cancelled++;
      else if (Set.of(
              "SUBMITTING",
              "SUBMITTED",
              "RUNNING",
              "UNKNOWN",
              "CANCEL_REQUESTED",
              "POST_PROCESSING",
              "REPORTING")
          .contains(state)) running++;
      else waiting++;
    }
    long total = ((Number) batch.get("total")).longValue(), done = success + failed + cancelled;
    var result = new LinkedHashMap<>(batch);
    result.remove("requestFingerprint");
    result.remove("idempotencyKey");
    result.put(
        "counts",
        Map.of(
            "total",
            total,
            "waiting",
            waiting,
            "running",
            running,
            "success",
            success,
            "failed",
            failed,
            "cancelled",
            cancelled));
    result.put("progress", total == 0 ? 0 : Math.round(done * 100.0 / total));
    result.put(
        "status",
        done == total
            ? (failed == 0
                ? (cancelled == total ? "CANCELLED" : "COMPLETED")
                : (failed == total ? "FAILED" : "PARTIAL_FAILED"))
            : (running > 0 ? "RUNNING" : "QUEUED"));
    result.put(
        "createdChildren", children.stream().filter(row -> row.get("jobNo") != null).count());
    result.put("children", children);
    result.put("backpressure", pressure.snapshot());
    return result;
  }
}
