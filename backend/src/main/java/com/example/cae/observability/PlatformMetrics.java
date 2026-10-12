package com.example.cae.observability;

import com.example.cae.events.EventStreamService;
import com.example.cae.job.domain.*;
import io.micrometer.core.instrument.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PlatformMetrics {
  private final JdbcTemplate jdbc;
  private final AtomicReference<Map<String, Double>> snapshot = new AtomicReference<>(Map.of());
  private final AtomicLong updated = new AtomicLong();
  private final Counter failures;

  public PlatformMetrics(JdbcTemplate jdbc, MeterRegistry registry, EventStreamService streams) {
    this.jdbc = jdbc;
    failures = registry.counter("cae.metrics.refresh.error");
    for (var status : JobStatus.values())
      for (var analysis : AnalysisType.values())
        gauge(
            registry,
            "cae.jobs.total",
            status + ":" + analysis,
            "status",
            status.name(),
            "analysis_type",
            analysis.name());
    for (String name :
        List.of(
            "jobs.running",
            "jobs.waiting.resource",
            "job.queue.seconds",
            "job.queue.samples",
            "reports.generated.total",
            "resource.reservation.total",
            "agent.offline.total",
            "outbox.oldest.pending.seconds",
            "batches.active")) gauge(registry, "cae." + name, name);
    Gauge.builder("cae.sse.connections", streams, EventStreamService::connectionCount)
        .register(registry);
    Gauge.builder("cae.metrics.last.success.timestamp.seconds", updated, AtomicLong::get)
        .register(registry);
    for (String node : List.of("node-a", "node-b", "node-c"))
      for (String field :
          List.of(
              "cpu.used",
              "cpu.reserved",
              "cpu.total",
              "memory.used.gb",
              "memory.reserved.gb",
              "memory.total.gb"))
        gauge(registry, "cae.node." + field, node + ":" + field, "node", node);
    for (String feature : List.of("OPTISTRUCT", "NVH", "CRASH", "CFD"))
      for (String field : List.of("reserved", "in.use", "total"))
        gauge(
            registry,
            "cae.license.tokens",
            feature + ":" + field,
            "feature",
            feature,
            "state",
            field);
  }

  private void gauge(MeterRegistry registry, String name, String key, String... tags) {
    Gauge.builder(name, snapshot, ref -> ref.get().getOrDefault(key, 0d))
        .tags(tags)
        .register(registry);
  }

  @Scheduled(fixedDelay = 5000)
  public void refresh() {
    try {
      Map<String, Double> values = new HashMap<>();
      jdbc.query(
          "SELECT status,analysis_type,COUNT(*) AS n FROM cae_job GROUP BY status,analysis_type",
          (org.springframework.jdbc.core.RowCallbackHandler)
              row ->
                  values.put(
                      row.getString("status") + ":" + row.getString("analysis_type"),
                      row.getDouble("n")));
      values.put("jobs.running", count("SELECT COUNT(*) FROM cae_job WHERE status='RUNNING'"));
      values.put(
          "jobs.waiting.resource",
          count("SELECT COUNT(*) FROM cae_job WHERE status='WAITING_RESOURCE'"));
      values.put(
          "job.queue.seconds",
          count(
              "SELECT COALESCE(AVG(TIMESTAMPDIFF(MICROSECOND,j.queued_at,x.started_at)/1000000),0) FROM cae_job j JOIN cae_job_execution x ON x.id=j.current_execution_id WHERE x.started_at>=j.queued_at"));
      values.put(
          "job.queue.samples",
          count(
              "SELECT COUNT(*) FROM cae_job j JOIN cae_job_execution x ON x.id=j.current_execution_id WHERE x.started_at>=j.queued_at"));
      values.put("reports.generated.total", count("SELECT COUNT(*) FROM cae_report"));
      values.put(
          "resource.reservation.total", count("SELECT COUNT(*) FROM cae_resource_reservation"));
      values.put(
          "agent.offline.total",
          count("SELECT COUNT(*) FROM cae_compute_node WHERE status='OFFLINE'"));
      values.put(
          "outbox.oldest.pending.seconds",
          count(
              "SELECT COALESCE(GREATEST(0,TIMESTAMPDIFF(MICROSECOND,MIN(created_at),UTC_TIMESTAMP(6))/1000000),0) FROM cae_outbox_event WHERE status IN ('NEW','RETRY','PUBLISHING')"));
      values.put(
          "batches.active",
          count(
              "SELECT COUNT(DISTINCT b.id) FROM cae_batch b JOIN cae_batch_item i ON i.batch_id=b.id LEFT JOIN cae_job j ON j.id=i.job_id WHERE i.status='PENDING' OR j.status IN ('CREATED','VALIDATING','READY','QUEUED','WAITING_RESOURCE','SUBMITTING','SUBMITTED','RUNNING','UNKNOWN','CANCEL_REQUESTED','POST_PROCESSING','REPORTING')"));
      jdbc.query(
          "SELECT * FROM cae_compute_node",
          (org.springframework.jdbc.core.RowCallbackHandler)
              row -> {
                String node = row.getString("node_id");
                for (String field :
                    List.of(
                        "cpu_used",
                        "cpu_reserved",
                        "cpu_total",
                        "memory_used_gb",
                        "memory_reserved_gb",
                        "memory_total_gb"))
                  values.put(node + ":" + field.replace('_', '.'), row.getDouble(field));
              });
      jdbc.query(
          "SELECT * FROM cae_license_pool",
          (org.springframework.jdbc.core.RowCallbackHandler)
              row -> {
                String feature = row.getString("feature");
                values.put(feature + ":reserved", row.getDouble("reserved_tokens"));
                values.put(feature + ":in.use", row.getDouble("in_use_tokens"));
                values.put(feature + ":total", row.getDouble("total_tokens"));
              });
      snapshot.set(Map.copyOf(values));
      updated.set(Instant.now().getEpochSecond());
    } catch (org.springframework.dao.DataAccessException error) {
      failures.increment();
    }
  }

  private double count(String sql) {
    return jdbc.queryForObject(sql, Double.class);
  }
}
