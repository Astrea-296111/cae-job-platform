package com.example.cae.result;

import com.example.cae.common.SqlRows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ResultRepository {
  private final JdbcTemplate jdbc;

  public ResultRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Map<String, Object>> find(long jobId, String version) {
    return jdbc
        .query(
            "SELECT * FROM cae_result_parse_record WHERE job_id=? AND parser_version=?",
            SqlRows.MAPPER,
            jobId,
            version)
        .stream()
        .findFirst();
  }

  public List<Map<String, Object>> history(long jobId) {
    return jdbc.query(
        "SELECT * FROM cae_result_parse_record WHERE job_id=? ORDER BY id", SqlRows.MAPPER, jobId);
  }

  public List<Map<String, Object>> metrics(long jobId, String version) {
    return version == null
        ? jdbc.query(
            "SELECT * FROM result_metric WHERE job_id=? ORDER BY parser_version,id",
            SqlRows.MAPPER,
            jobId)
        : jdbc.query(
            "SELECT * FROM result_metric WHERE job_id=? AND parser_version=? ORDER BY id",
            SqlRows.MAPPER,
            jobId,
            version);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> request(
      long jobId,
      long executionId,
      String version,
      String checksum,
      String analysis,
      String condition,
      int subcase,
      String maturity) {
    Optional<Map<String, Object>> old = find(jobId, version);
    if (old.isPresent()) {
      Map<String, Object> row = old.get();
      if (((Number) row.get("executionId")).longValue() != executionId)
        throw new IllegalArgumentException(
            "A parser version belongs to another immutable execution");
      if (!"FAILED".equals(row.get("status"))) {
        if (!row.get("rawChecksum").equals(checksum))
          throw new IllegalArgumentException(
              "A parser version belongs to different raw result bytes");
        return row;
      }
      jdbc.update(
          "UPDATE cae_result_parse_record SET status='REQUESTED',raw_checksum=?,analysis_type=?,condition_code=?,subcase=?,maturity=?,failure_message=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
          checksum,
          analysis,
          condition,
          subcase,
          maturity,
          SqlRows.id(row));
    } else
      jdbc.update(
          "INSERT INTO cae_result_parse_record(job_id,execution_id,parser_version,raw_checksum,analysis_type,condition_code,subcase,maturity) VALUES(?,?,?,?,?,?,?,?)",
          jobId,
          executionId,
          version,
          checksum,
          analysis,
          condition,
          subcase,
          maturity);
    return find(jobId, version).orElseThrow();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> complete(Map<String, Object> record, ParseResult parsed) {
    long id = SqlRows.id(record);
    for (CanonicalMetric metric : parsed.metrics())
      jdbc.update(
          "INSERT INTO result_metric(parse_record_id,job_id,execution_id,parser_version,metric_code,metric_name,value_num,value_text,unit,dimension,status,condition_code,subcase) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          record.get("jobId"),
          record.get("executionId"),
          record.get("parserVersion"),
          metric.metricCode(),
          metric.metricName(),
          metric.valueNum(),
          metric.valueText(),
          metric.unit(),
          metric.dimension(),
          metric.status(),
          metric.conditionCode(),
          metric.subcase());
    jdbc.update(
        "UPDATE cae_result_parse_record SET status='COMPLETE',metric_count=?,failure_message=NULL,completed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=? AND status='REQUESTED'",
        parsed.metrics().size(),
        id);
    return find(((Number) record.get("jobId")).longValue(), record.get("parserVersion").toString())
        .orElseThrow();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markPostRequested(long id) {
    if (jdbc.update(
            "UPDATE cae_result_parse_record SET post_requested_at=UTC_TIMESTAMP(6) WHERE id=? AND status='COMPLETE' AND post_requested_at IS NULL",
            id)
        != 1)
      throw new IllegalStateException("Post command was already requested for this parse version");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void failed(
      long jobId,
      long executionId,
      String version,
      String analysis,
      String condition,
      int subcase,
      String checksum,
      String reason) {
    jdbc.update(
        "INSERT INTO cae_result_parse_record(job_id,execution_id,parser_version,raw_checksum,analysis_type,condition_code,subcase,maturity,status,failure_message) VALUES(?,?,?,?,?,?,?,?,'FAILED',?) ON DUPLICATE KEY UPDATE failure_message=IF(status='COMPLETE',failure_message,VALUES(failure_message)),status=IF(status='COMPLETE',status,'FAILED'),updated_at=IF(status='COMPLETE',updated_at,UTC_TIMESTAMP(6))",
        jobId,
        executionId,
        version,
        checksum,
        analysis,
        condition,
        subcase,
        analysis.equals("STRUCTURE") ? "COMPLETE" : "PILOT",
        bounded(reason));
  }

  private static String bounded(String value) {
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }
}
