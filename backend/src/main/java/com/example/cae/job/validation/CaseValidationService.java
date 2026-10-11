package com.example.cae.job.validation;

import com.example.cae.common.ApiException;
import com.example.cae.common.PlatformFailure;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.repository.JobRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CaseValidationService {
  private final JobRepository jobs;
  private final InputSnapshotService snapshots;
  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;

  public CaseValidationService(
      JobRepository jobs,
      InputSnapshotService snapshots,
      TransactionTemplate transactions,
      JdbcTemplate jdbc) {
    this.jobs = jobs;
    this.snapshots = snapshots;
    this.transactions = transactions;
    this.jdbc = jdbc;
  }

  /** Filesystem work runs outside database transactions; both final outcomes are persisted. */
  public Map<String, Object> validate(Map<String, Object> requested) {
    long id = SqlRows.id(requested);
    JobStatus status = JobStatus.valueOf(requested.get("status").toString());
    if (status == JobStatus.READY) {
      snapshots.verifySnapshot(requested);
      return jobs.findById(id);
    }
    if (status != JobStatus.CREATED
        && status != JobStatus.VALIDATION_FAILED
        && status != JobStatus.CANCELLED)
      throw ApiException.conflict(
          "Job can be validated only from CREATED, VALIDATION_FAILED or CANCELLED");
    Map<String, Object> validating =
        jobs.transition(requested, JobStatus.VALIDATING, "VALIDATION_STARTED", Map.of());
    try {
      Map<String, Object> validationInput = new LinkedHashMap<>(validating);
      validationInput.put(
          "modelRevision",
          jdbc.queryForObject(
              "SELECT revision FROM cae_model_revision WHERE id=?",
              String.class,
              validating.get("modelRevisionId")));
      InputSnapshotService.Snapshot snapshot = snapshots.create(validationInput);
      return transactions.execute(
          transaction -> {
            Map<String, Object> current = jobs.findById(id);
            if (!current.get("status").equals(JobStatus.VALIDATING.name()))
              throw ApiException.conflict("Validation was superseded by another operation");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("workspace", snapshot.workspace());
            metadata.put("inputChecksum", snapshot.checksum());
            metadata.put("inputManifestUri", snapshot.manifestUri());
            metadata.put("failureCode", null);
            metadata.put("failureMessage", null);
            Map<String, Object> patched = jobs.patch(current, metadata);
            return jobs.transition(
                patched,
                JobStatus.READY,
                "JOB_READY",
                Map.of("inputChecksum", snapshot.checksum(), "fileCount", snapshot.files().size()));
          });
    } catch (PlatformFailure failure) {
      return transactions.execute(
          transaction -> {
            Map<String, Object> current = jobs.findById(id);
            if (!current.get("status").equals(JobStatus.VALIDATING.name()))
              throw ApiException.conflict("Validation was superseded by another operation");
            Map<String, Object> patched =
                jobs.patch(
                    current,
                    Map.of(
                        "failureCode",
                        failure.code().name(),
                        "failureMessage",
                        failure.getMessage()));
            return jobs.transition(
                patched, JobStatus.VALIDATION_FAILED, "VALIDATION_FAILED", failure.toMap());
          });
    }
  }
}
