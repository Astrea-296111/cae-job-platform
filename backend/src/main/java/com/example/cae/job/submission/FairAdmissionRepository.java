package com.example.cae.job.submission;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FairAdmissionRepository {
  private final JdbcTemplate jdbc;

  public FairAdmissionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Per-discipline rank prevents a long high-priority discipline queue from hiding others. */
  public List<Long> waiting(int limit) {
    return jdbc.queryForList(
        "SELECT id FROM (SELECT id,analysis_type,ROW_NUMBER() OVER(PARTITION BY analysis_type ORDER BY priority+GREATEST(0,TIMESTAMPDIFF(SECOND,COALESCE(queued_at,created_at),UTC_TIMESTAMP(6)))*0.02 DESC,id) AS position FROM cae_job WHERE status IN ('QUEUED','WAITING_RESOURCE')) ranked ORDER BY position,analysis_type,id LIMIT ?",
        Long.class,
        limit);
  }

  public List<Long> cancellations() {
    return jdbc.queryForList(
        "SELECT id FROM cae_job WHERE status='CANCEL_REQUESTED' ORDER BY updated_at,id LIMIT 8",
        Long.class);
  }
}
