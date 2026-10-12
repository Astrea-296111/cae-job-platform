package com.example.cae.result;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.cae.batch.*;
import com.example.cae.job.submission.FairAdmissionRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    properties = {
      "cae.mq.listeners-enabled=false",
      "cae.batch.active-limit=32",
      "cae.batch.pending-limit=1000000"
    })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatchAdmissionIntegrationTest extends ResultTestSupport {
  @Autowired BatchFeedService feeder;
  @Autowired BackpressureService pressure;
  @Autowired FairAdmissionRepository fair;
  @Autowired com.example.cae.outbox.OutboxPublisher publisher;
  private final List<String> owned = new ArrayList<>();

  @BeforeEach
  void configureOwnedHeadroom() {
    // Other suites retain their rows in this harness schema. Measure their baseline, never mutate
    // them.
    org.springframework.test.util.ReflectionTestUtils.setField(pressure, "pendingLimit", 1000000);
    int baseline = ((Number) pressure.snapshot().get("activeJobs")).intValue();
    org.springframework.test.util.ReflectionTestUtils.setField(
        pressure, "activeLimit", baseline + 32);
  }

  @AfterEach
  void clearOwnedPending() {
    for (String no : owned) {
      jdbc.update(
          "UPDATE cae_job j JOIN cae_batch b ON b.id=j.batch_id SET j.status='CANCELLED' WHERE b.batch_no=?",
          no);
      jdbc.update(
          "UPDATE cae_batch_item i JOIN cae_batch b ON b.id=i.batch_id SET i.status='REJECTED' WHERE b.batch_no=? AND i.status='PENDING'",
          no);
    }
    owned.clear();
  }

  private Map<String, Object> body(int count, String key) {
    return Map.of(
        "name",
        "batch acceptance",
        "projectId",
        1,
        "idempotencyKey",
        key,
        "jobs",
        Collections.nCopies(
            count,
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
                "SMALL",
                "casePath",
                "public-test-case")));
  }

  private Map<String, Object> create(Map<String, Object> body) throws Exception {
    var batch =
        json.readMap(
            mvc.perform(
                    post("/api/v1/batches")
                        .with(httpBasic("engineer", "engineer123"))
                        .contentType("application/json")
                        .content(json.write(body)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
    owned.add(batch.get("batchNo").toString());
    return batch;
  }

  @Test
  void twentyFourIdenticalChildrenAreDurableAndAdmittedOnlyTwoPerParentPerScan() throws Exception {
    var batch = create(body(24, UUID.randomUUID().toString()));
    long id = ((Number) batch.get("id")).longValue();
    assertThat(((Number) batch.get("createdChildren")).intValue()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT item_index) FROM cae_batch_item WHERE batch_id=?",
                Integer.class,
                id))
        .isEqualTo(24);
    assertThat(feeder.feed()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM cae_job WHERE batch_id=?", Integer.class, id))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_job_execution x JOIN cae_job j ON j.id=x.job_id WHERE j.batch_id=?",
                Integer.class,
                id))
        .isZero();
    for (int i = 0; i < 11; i++) feeder.feed();
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM cae_job WHERE batch_id=?", Integer.class, id))
        .isEqualTo(24);
    var detail =
        json.readMap(
            mvc.perform(
                    get("/api/v1/batches/" + batch.get("batchNo"))
                        .with(httpBasic("engineer", "engineer123")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(json.readMap(detail.get("counts"))).containsEntry("waiting", 24);
    mvc.perform(
            get("/api/v1/batches/" + batch.get("batchNo")).with(user("outsider").roles("ENGINEER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void concurrentBatchIntentAndChangedPayloadAreFenced() throws Exception {
    String key = UUID.randomUUID().toString();
    var encoded = json.write(body(20, key));
    var callers = Executors.newFixedThreadPool(6);
    try {
      var tasks = new ArrayList<Callable<String>>();
      for (int i = 0; i < 12; i++)
        tasks.add(
            () ->
                mvc.perform(
                        post("/api/v1/batches")
                            .with(httpBasic("engineer", "engineer123"))
                            .contentType("application/json")
                            .content(encoded))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
      var numbers = new HashSet<String>();
      for (var future : callers.invokeAll(tasks))
        numbers.add(json.readMap(future.get()).get("batchNo").toString());
      owned.addAll(numbers);
      assertThat(numbers).hasSize(1);
    } finally {
      callers.shutdownNow();
    }
    mvc.perform(
            post("/api/v1/batches")
                .with(httpBasic("engineer", "engineer123"))
                .contentType("application/json")
                .content(json.write(body(21, key))))
        .andExpect(status().isConflict());
  }

  @Test
  void pressurePausesAndResumesWithoutLosingIntents() throws Exception {
    var batch = create(body(40, UUID.randomUUID().toString()));
    long id = ((Number) batch.get("id")).longValue();
    for (int i = 0; i < 16; i++) feeder.feed();
    assertThat(pressure.paused()).isTrue();
    assertThat(feeder.feed()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_batch_item WHERE batch_id=? AND status='PENDING'",
                Integer.class,
                id))
        .isGreaterThanOrEqualTo(8);
    jdbc.update("UPDATE cae_job SET status='CANCELLED' WHERE batch_id=?", id);
    assertThat(pressure.paused()).isFalse();
    assertThat(feeder.feed()).isEqualTo(2);
  }

  @Test
  void badChildAdmissionIsRecordedAndOtherChildrenContinue() throws Exception {
    var values = new ArrayList<Map<String, Object>>();
    values.add(
        Map.of(
            "projectId",
            1,
            "modelRevisionId",
            999999,
            "analysisType",
            "STRUCTURE",
            "conditionCode",
            "BIW_TORSION",
            "resourceProfile",
            "SMALL"));
    values.add(
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
            "SMALL"));
    var batch =
        create(
            Map.of(
                "name",
                "partial",
                "projectId",
                1,
                "idempotencyKey",
                UUID.randomUUID().toString(),
                "jobs",
                values));
    assertThat(feeder.feed()).isEqualTo(2);
    long id = ((Number) batch.get("id")).longValue();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_batch_item WHERE batch_id=? AND status='REJECTED'",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM cae_job WHERE batch_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void disciplineRanksPreventLargeStructureQueueFromHidingPilots() throws Exception {
    var batch = create(body(20, UUID.randomUUID().toString()));
    for (int i = 0; i < 10; i++) feeder.feed();
    long id = ((Number) batch.get("id")).longValue();
    jdbc.update("UPDATE cae_job SET status='QUEUED',priority=100 WHERE batch_id=?", id);
    var pilot = completed("NVH", "CABIN_NVH", 1);
    jdbc.update("UPDATE cae_job SET status='QUEUED',priority=0 WHERE id=?", pilot.jobId());
    assertThat(fair.waiting(4)).contains(pilot.jobId());
    jdbc.update("UPDATE cae_job SET status='POST_PROCESSING' WHERE id=?", pilot.jobId());
  }

  @Test
  void outboxPressureResumesAfterRealOwnedPublisherConfirms() throws Exception {
    var batch = create(body(20, UUID.randomUUID().toString()));
    long id = ((Number) batch.get("id")).longValue();
    int baseline = ((Number) pressure.snapshot().get("outboxPending")).intValue();
    org.springframework.test.util.ReflectionTestUtils.setField(
        pressure, "pendingLimit", baseline + 1);
    assertThat(feeder.feed()).isEqualTo(1);
    assertThat(pressure.paused()).isTrue();
    assertThat(feeder.feed()).isZero();
    var ids =
        jdbc.queryForList(
            "SELECT e.id FROM cae_outbox_event e JOIN cae_job j ON j.id=e.aggregate_id WHERE j.batch_id=? AND e.status='NEW'",
            Long.class,
            id);
    assertThat(ids).hasSize(3);
    assertThat(publisher.publishIds(ids)).isEqualTo(3);
    assertThat(pressure.paused()).isFalse();
    assertThat(feeder.feed()).isEqualTo(1);
  }

  @Test
  void parallelFeedersRespectCapacityAndUniqueChildAssociation() throws Exception {
    var batch = create(body(40, UUID.randomUUID().toString()));
    long id = ((Number) batch.get("id")).longValue();
    var pool = Executors.newFixedThreadPool(8);
    try {
      var work = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 30; i++) work.add(() -> feeder.feed());
      for (var future : pool.invokeAll(work)) future.get();
    } finally {
      pool.shutdownNow();
    }
    assertThat(pressure.paused()).isTrue();
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM cae_job WHERE batch_id=?", Integer.class, id))
        .isEqualTo(32);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT job_id) FROM cae_batch_item WHERE batch_id=? AND status='CREATED'",
                Integer.class,
                id))
        .isEqualTo(32);
  }
}
