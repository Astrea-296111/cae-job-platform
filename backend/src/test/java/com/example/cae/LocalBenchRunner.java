package com.example.cae;

import com.example.cae.common.*;
import com.example.cae.job.api.CreateJobRequest;
import com.example.cae.job.application.JobApplicationService;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.outbox.OutboxPublisher;
import com.example.cae.resource.ResourceReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual isolated MySQL resource transactions and Rabbit publisher confirms; no mock timing. */
public final class LocalBenchRunner {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("cae-local-bench-");
    try (var context =
        new SpringApplicationBuilder(CaeApplication.class)
            .run(
                "--server.port=0",
                "--cae.background.enabled=false",
                "--cae.mq.listeners-enabled=false",
                "--DATA_ROOT=" + root)) {
      var jdbc = context.getBean(JdbcTemplate.class);
      var tx = context.getBean(TransactionTemplate.class);
      var application = context.getBean(JobApplicationService.class);
      var jobs = context.getBean(JobRepository.class);
      var resources = context.getBean(ResourceReservationService.class);
      var mapper = context.getBean(ObjectMapper.class);
      var publisher = context.getBean(OutboxPublisher.class);
      var admin =
          UsernamePasswordAuthenticationToken.authenticated(
              "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
      var request =
          mapper.convertValue(
              Map.of(
                  "projectId",
                  1,
                  "modelRevisionId",
                  2,
                  "analysisType",
                  "STRUCTURE",
                  "conditionCode",
                  "BIW_TORSION",
                  "resourceProfile",
                  "SMALL"),
              CreateJobRequest.class);
      List<Long> ids = new ArrayList<>();
      for (int i = 0; i < 50; i++)
        ids.add(SqlRows.id(application.create(admin, request, "bench-" + UUID.randomUUID())));
      jdbc.update("UPDATE cae_compute_node SET drained=TRUE");
      jdbc.update(
          "UPDATE cae_compute_node SET drained=FALSE,status='ONLINE',workspace_reachable=TRUE,last_heartbeat=UTC_TIMESTAMP(6),observed_cpu_used=0,observed_memory_used_gb=0 WHERE node_id='node-a'");
      var ready = new CountDownLatch(50);
      var start = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(50);
      List<Future<Map<String, Object>>> pending = new ArrayList<>();
      for (long id : ids)
        pending.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  long at = System.nanoTime();
                  try {
                    var reserved =
                        tx.execute(
                            status -> resources.reserve(id, "node-a", 4, 8, "OPTISTRUCT", 1));
                    return Map.of(
                        "ms",
                        (System.nanoTime() - at) / 1000000d,
                        "outcome",
                        reserved.isPresent() ? "admitted" : "denied",
                        "reservationId",
                        reserved.map(ResourceReservationService.Reservation::id).orElse(0L));
                  } catch (RuntimeException failure) {
                    return Map.of(
                        "ms",
                        (System.nanoTime() - at) / 1000000d,
                        "outcome",
                        "error",
                        "error",
                        failure.getClass().getSimpleName());
                  }
                }));
      if (!ready.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Benchmark barrier timeout");
      start.countDown();
      List<Map<String, Object>> samples = new ArrayList<>();
      for (var future : pending) samples.add(future.get(30, TimeUnit.SECONDS));
      pool.shutdownNow();
      long accepted = samples.stream().filter(row -> row.get("outcome").equals("admitted")).count();
      var peak =
          jdbc.queryForMap(
              "SELECT cpu_reserved,memory_reserved_gb,cpu_total,memory_total_gb FROM cae_compute_node WHERE node_id='node-a'");
      var license =
          jdbc.queryForMap(
              "SELECT reserved_tokens,total_tokens FROM cae_license_pool WHERE feature='OPTISTRUCT'");
      if (accepted != 4
          || ((Number) peak.get("cpu_reserved")).intValue() != 16
          || ((Number) peak.get("memory_reserved_gb")).intValue() != 32
          || ((Number) license.get("reserved_tokens")).intValue() != 4)
        throw new IllegalStateException("Resource admission invariant failed");
      for (var sample : samples)
        if (sample.get("outcome").equals("admitted"))
          resources.release(((Number) sample.get("reservationId")).longValue());
      List<Long> events =
          tx.execute(
              status -> {
                for (int i = 0; i < 1000; i++)
                  jobs.recordEvent(ids.get(0), "LOCAL_BENCH_EVENT", Map.of("index", i));
                return jdbc.queryForList(
                    "SELECT id FROM cae_outbox_event WHERE event_type='LOCAL_BENCH_EVENT' ORDER BY id",
                    Long.class);
              });
      List<Map<String, Object>> publishing = new ArrayList<>();
      for (long event : events) {
        long at = System.nanoTime();
        try {
          int count = publisher.publishIds(List.of(event));
          publishing.add(
              Map.of(
                  "ms",
                  (System.nanoTime() - at) / 1000000d,
                  "outcome",
                  count == 1 ? "confirmed" : "error"));
        } catch (RuntimeException error) {
          publishing.add(
              Map.of(
                  "ms",
                  (System.nanoTime() - at) / 1000000d,
                  "outcome",
                  "error",
                  "error",
                  error.getClass().getSimpleName()));
        }
      }
      int confirmed =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM cae_outbox_event WHERE event_type='LOCAL_BENCH_EVENT' AND status='SENT'",
              Integer.class);
      if (confirmed != 1000) throw new IllegalStateException("Missing publisher confirms");
      var result =
          Map.of(
              "label",
              "[LOCAL-BENCH]",
              "scope",
              "owned MySQL schema/Redis namespace/Rabbit vhost; actual ledger transactions and persistent publisher confirms",
              "resourceClaims",
              Map.of(
                  "samples",
                  samples,
                  "admitted",
                  accepted,
                  "denied",
                  samples.stream().filter(row -> row.get("outcome").equals("denied")).count(),
                  "peakNode",
                  peak,
                  "peakLicense",
                  license),
              "outboxPublish",
              Map.of("samples", publishing, "confirmed", confirmed));
      Path output = Path.of(System.getenv("CAE_BENCH_JAVA_RESULT"));
      Files.createDirectories(output.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }
}
