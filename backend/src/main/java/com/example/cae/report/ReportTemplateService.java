package com.example.cae.report;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.job.repository.JobRepository;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.stereotype.Service
public class ReportTemplateService {
  private final PipelineRepository repository;
  private final ProjectAccess access;
  private final JobRepository jobs;
  private final TransactionTemplate tx;

  public ReportTemplateService(
      PipelineRepository repository,
      ProjectAccess access,
      JobRepository jobs,
      TransactionTemplate tx) {
    this.repository = repository;
    this.access = access;
    this.jobs = jobs;
    this.tx = tx;
  }

  public List<Map<String, Object>> templates() {
    return repository.templates();
  }

  public List<Map<String, Object>> enabled(Authentication user, String code, boolean enabled) {
    if (!access.admin(user)) throw ApiException.forbidden();
    return tx.execute(
        status -> {
          repository.template(code, enabled);
          jobs.audit(
              user.getName(),
              "REPORT_TEMPLATE_UPDATE",
              "REPORT_TEMPLATE",
              code,
              null,
              Map.of("enabled", enabled));
          return repository.templates();
        });
  }
}
