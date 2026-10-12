package com.example.cae.report;

import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.JobStatus;
import com.example.cae.job.repository.JobRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Job locks guard the one pipeline allowed to advance its main state. */
@Component
public class PipelineBinding {
  private final JobRepository jobs;

  public PipelineBinding(JobRepository jobs) {
    this.jobs = jobs;
  }

  public Map<String, Object> completed(Map<String, Object> job, Map<String, Object> parse) {
    if (job.get("activePipelineParseId") == null
        || Set.of("POST_PROCESS_FAILED", "REPORT_FAILED").contains(job.get("status"))) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("activePipelineParseId", SqlRows.id(parse));
      fields.put("activePostVersion", 1);
      fields.put("activeReportVersion", 1);
      fields.put("pipelineGeneration", ((Number) job.get("pipelineGeneration")).longValue() + 1);
      fields.put("failureCode", null);
      fields.put("failureMessage", null);
      job = jobs.patch(job, fields);
      if (Set.of("POST_PROCESS_FAILED", "REPORT_FAILED").contains(job.get("status")))
        job =
            jobs.transition(
                job,
                JobStatus.POST_PROCESSING,
                "RESULT_PARSE_RECOVERED",
                Map.of("parseRecordId", SqlRows.id(parse)));
    }
    return job;
  }

  public boolean active(Map<String, Object> job, Map<String, Object> stage) {
    return java.util.Objects.equals(job.get("activePipelineParseId"), stage.get("parseRecordId"))
        && ((Number) job.get("pipelineGeneration")).longValue()
            == ((Number) stage.get("pipelineGeneration")).longValue()
        && ((Number)
                    job.get(
                        stage.get("kind").equals("POST")
                            ? "activePostVersion"
                            : "activeReportVersion"))
                .intValue()
            == ((Number) stage.get("stageVersion")).intValue();
  }

  public boolean canFailParse(Map<String, Object> job, long parseId) {
    return job.get("activePipelineParseId") == null
        || ((Number) job.get("activePipelineParseId")).longValue() == parseId;
  }
}
