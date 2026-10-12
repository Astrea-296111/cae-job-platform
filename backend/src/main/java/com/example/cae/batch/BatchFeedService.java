package com.example.cae.batch;

import com.example.cae.common.*;
import com.example.cae.job.api.CreateJobRequest;
import com.example.cae.job.application.JobApplicationService;
import com.example.cae.job.repository.JobRepository;
import com.example.cae.job.submission.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BatchFeedService {
  private final BatchRepository batches;
  private final JobApplicationService application;
  private final JobCommandService commands;
  private final JobRepository jobs;
  private final BackpressureService pressure;
  private final UserDetailsService users;
  private final ObjectMapper mapper;
  private final JsonCodec json;
  private final TransactionTemplate tx;
  private final boolean background;

  public BatchFeedService(
      BatchRepository batches,
      JobApplicationService application,
      JobCommandService commands,
      JobRepository jobs,
      BackpressureService pressure,
      UserDetailsService users,
      ObjectMapper mapper,
      JsonCodec json,
      TransactionTemplate tx,
      @Value("${cae.background.enabled:true}") boolean background) {
    this.batches = batches;
    this.application = application;
    this.commands = commands;
    this.jobs = jobs;
    this.pressure = pressure;
    this.users = users;
    this.mapper = mapper;
    this.json = json;
    this.tx = tx;
    this.background = background;
  }

  @Scheduled(fixedDelayString = "${cae.batch.scan-ms:1000}")
  public void scan() {
    if (background) feed();
  }

  public int feed() {
    int admitted = 0;
    for (long batch : batches.pendingBatches())
      for (int i = 0; i < 2; i++) {
        if (pressure.paused()) return admitted;
        if (one(batch)) admitted++;
      }
    return admitted;
  }

  private boolean one(long batch) {
    AtomicLong claimed = new AtomicLong();
    try {
      return Boolean.TRUE.equals(
          tx.execute(
              status -> {
                var next = batches.lockNext(batch);
                if (next.isEmpty() || pressure.paused()) return false;
                var item = next.get();
                long id = SqlRows.id(item);
                claimed.set(id);
                var account = users.loadUserByUsername(item.get("submitterId").toString());
                if (!account.isEnabled() || !account.isAccountNonLocked())
                  throw ApiException.forbidden();
                var user =
                    UsernamePasswordAuthenticationToken.authenticated(
                        account.getUsername(), null, account.getAuthorities());
                var request =
                    mapper.convertValue(
                        json.readMap(item.get("requestJson")), CreateJobRequest.class);
                String key = "batch-" + batch + "-" + item.get("itemIndex");
                var job = application.create(user, request, key);
                job = jobs.patch(job, Map.of("batchId", batch));
                commands.submit(
                    user, job.get("jobNo").toString(), new SubmissionRequest(key, null));
                jobs.recordEvent(
                    SqlRows.id(job),
                    "BATCH_CHILD_ADMITTED",
                    Map.of("batchId", batch, "itemIndex", item.get("itemIndex")));
                batches.completed(id, batch, SqlRows.id(job), null, null);
                return true;
              }));
    } catch (ApiException rejected) {
      // The joined creation transaction has rolled back. Record only this item in a fresh
      // transaction.
      return Boolean.TRUE.equals(
          tx.execute(
              status -> {
                var item = batches.lockNext(batch);
                if (item.isEmpty() || SqlRows.id(item.get()) != claimed.get()) return false;
                batches.completed(
                    claimed.get(), batch, null, rejected.code(), rejected.getMessage());
                return true;
              }));
    }
  }
}
