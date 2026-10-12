package com.example.cae.events;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import com.example.cae.job.repository.JobRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Notifications read committed MySQL events; reconnect always refreshes authoritative views. */
@Service
public class EventStreamService {
  private final JdbcTemplate jdbc;
  private final ProjectAccess access;
  private final JobRepository jobs;
  private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor senders =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(256),
          new ThreadPoolExecutor.AbortPolicy());

  public EventStreamService(JdbcTemplate jdbc, ProjectAccess access, JobRepository jobs) {
    this.jdbc = jdbc;
    this.access = access;
    this.jobs = jobs;
  }

  public synchronized SseEmitter connect(
      Authentication user, String jobNo, Long project, String last) throws IOException {
    if (jobNo != null) access.requireJob(user, jobs.findByNo(jobNo));
    if (project != null) access.require(user, project);
    if (connections.size() >= 200
        || connections.values().stream().filter(c -> c.username.equals(user.getName())).count()
            >= 60)
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS, "STREAM_LIMIT", "Too many open event streams");
    long maximum = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM cae_job_event", Long.class);
    long cursor = maximum;
    if (last != null) {
      try {
        cursor = Math.min(maximum, Math.max(0, Long.parseLong(last)));
      } catch (NumberFormatException invalid) {
        throw ApiException.invalid("Invalid event cursor");
      }
    }
    var emitter = new SseEmitter(30 * 60 * 1000L);
    String key = UUID.randomUUID().toString();
    var connection =
        new Connection(emitter, user.getName(), access.admin(user), jobNo, project, cursor);
    connections.put(key, connection);
    emitter.onCompletion(() -> connections.remove(key));
    emitter.onTimeout(
        () -> {
          connections.remove(key);
          emitter.complete();
        });
    emitter.onError(error -> connections.remove(key));
    try {
      emitter.send(
          SseEmitter.event()
              .name("ready")
              .data(Map.of("cursor", maximum, "asOf", Instant.now().toString())));
    } catch (IOException error) {
      connections.remove(key);
      throw error;
    }
    return emitter;
  }

  @Scheduled(fixedDelay = 1000)
  public void poll() {
    connections.forEach(
        (key, connection) -> {
          if (!connection.sending.compareAndSet(false, true)) return;
          try {
            senders.execute(() -> send(key, connection));
          } catch (RejectedExecutionException full) {
            connection.sending.set(false);
          }
        });
  }

  private void send(String key, Connection c) {
    try {
      var events =
          jdbc.query(
              "SELECT e.id AS event_id,e.event_type AS type,e.job_id,j.job_no,j.status,j.version,e.created_at AS occurred_at FROM cae_job_event e JOIN cae_job j ON j.id=e.job_id WHERE e.id>? AND (? IS NULL OR j.job_no=?) AND (? IS NULL OR j.project_id=?) AND (? OR EXISTS(SELECT 1 FROM cae_project_member m WHERE m.project_id=j.project_id AND m.username=?)) ORDER BY e.id LIMIT 100",
              SqlRows.MAPPER,
              c.cursor,
              c.jobNo,
              c.jobNo,
              c.project,
              c.project,
              c.admin,
              c.username);
      for (var event : events) {
        long id = ((Number) event.get("eventId")).longValue();
        c.emitter.send(SseEmitter.event().id(Long.toString(id)).name("job").data(event));
        c.cursor = id;
      }
      if (System.nanoTime() - c.heartbeat > TimeUnit.SECONDS.toNanos(15)) {
        c.emitter.send(SseEmitter.event().comment("heartbeat"));
        c.heartbeat = System.nanoTime();
      }
    } catch (IOException disconnected) {
      // Spring already completes an emitter when a transport write fails.
      connections.remove(key);
    } catch (IllegalStateException closed) {
      connections.remove(key);
    } catch (org.springframework.dao.DataAccessException unavailable) {
      /* Keep the cursor for recovery. */
    } finally {
      c.sending.set(false);
    }
  }

  public int connectionCount() {
    return connections.size();
  }

  @PreDestroy
  public void close() {
    connections.values().forEach(c -> c.emitter.complete());
    connections.clear();
    senders.shutdownNow();
  }

  private static class Connection {
    final SseEmitter emitter;
    final String username;
    final boolean admin;
    final String jobNo;
    final Long project;
    final AtomicBoolean sending = new AtomicBoolean();
    volatile long cursor;
    volatile long heartbeat = System.nanoTime();

    Connection(
        SseEmitter emitter,
        String username,
        boolean admin,
        String jobNo,
        Long project,
        long cursor) {
      this.emitter = emitter;
      this.username = username;
      this.admin = admin;
      this.jobNo = jobNo;
      this.project = project;
      this.cursor = cursor;
    }
  }
}
