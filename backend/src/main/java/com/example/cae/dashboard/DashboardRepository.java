package com.example.cae.dashboard;

import com.example.cae.common.SqlRows;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {
  private final JdbcTemplate jdbc;

  public DashboardRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String FILTER =
      "(? IS NULL OR j.project_id=?) AND (? IS NULL OR j.created_at>=?) AND (? IS NULL OR j.created_at<?) AND (? OR EXISTS(SELECT 1 FROM cae_project_member m WHERE m.project_id=j.project_id AND m.username=?))";

  public Map<String, Object> counts(Object[] values) {
    return jdbc.query(
            "SELECT COUNT(*) AS total,COALESCE(SUM(j.status='RUNNING'),0) AS running,COALESCE(SUM(j.status='QUEUED'),0) AS queued,COALESCE(SUM(j.status='WAITING_RESOURCE'),0) AS waiting_resource,COALESCE(SUM(j.status IN ('VALIDATION_FAILED','SUBMIT_FAILED','RUNTIME_FAILED','POST_PROCESS_FAILED','REPORT_FAILED')),0) AS failed,COALESCE(SUM(j.status='SUCCESS'),0) AS success,COALESCE(SUM(j.status='UNKNOWN'),0) AS unknown_count,COALESCE(SUM(j.status='CANCELLED'),0) AS cancelled FROM cae_job j WHERE "
                + FILTER,
            SqlRows.MAPPER,
            values)
        .get(0);
  }

  public List<Map<String, Object>> distribution(Object[] values) {
    return jdbc.query(
        "SELECT j.analysis_type,j.status,COUNT(*) AS count FROM cae_job j WHERE "
            + FILTER
            + " GROUP BY j.analysis_type,j.status ORDER BY j.analysis_type,j.status",
        SqlRows.MAPPER,
        values);
  }

  public Map<String, Object> queueTime(Object[] values) {
    return jdbc.query(
            "SELECT AVG(IF(x.started_at>=j.queued_at,TIMESTAMPDIFF(MICROSECOND,j.queued_at,x.started_at)/1000000,NULL)) AS value,COALESCE(SUM(x.started_at>=j.queued_at),0) AS sample_count,COALESCE(SUM(x.started_at IS NOT NULL AND (j.queued_at IS NULL OR x.started_at<j.queued_at)),0) AS excluded_count FROM cae_job j LEFT JOIN cae_job_execution x ON x.id=j.current_execution_id WHERE "
                + FILTER,
            SqlRows.MAPPER,
            values)
        .get(0);
  }

  public List<Map<String, Object>> recent(Object[] values) {
    return jdbc.query(
        "SELECT j.job_no,j.analysis_type,j.condition_code,j.status,j.created_at FROM cae_job j WHERE "
            + FILTER
            + " ORDER BY j.id DESC LIMIT 8",
        SqlRows.MAPPER,
        values);
  }
}
