package com.example.cae.result;

import static org.assertj.core.api.Assertions.*;

import com.example.cae.observability.PlatformMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "cae.mq.listeners-enabled=false")
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformMetricsIntegrationTest extends ResultTestSupport {
  @Autowired PlatformMetrics metrics;
  @Autowired MeterRegistry registry;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;

  @Test
  void snapshotUsesDatabaseCountsAndExportsFixedLowCardinalityLabels() throws Exception {
    completed();
    metrics.refresh();
    double expected =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cae_job WHERE status='POST_PROCESSING' AND analysis_type='STRUCTURE'",
            Double.class);
    assertThat(
            registry
                .find("cae.jobs.total")
                .tags("status", "POST_PROCESSING", "analysis_type", "STRUCTURE")
                .gauge()
                .value())
        .isEqualTo(expected);
    assertThat(registry.find("cae.resource.reservation.total").gauge().value())
        .isEqualTo(
            jdbc.queryForObject("SELECT COUNT(*) FROM cae_resource_reservation", Double.class));
    assertThat(registry.find("cae.node.cpu.reserved").tag("node", "node-a").gauge().value())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT cpu_reserved FROM cae_compute_node WHERE node_id='node-a'", Double.class));
    assertThat(registry.find("cae.metrics.last.success.timestamp.seconds").gauge().value())
        .isPositive();
    assertThat(registry.find("cae.jobs.total").gauges()).hasSize(76);
    assertThat(registry.find("cae.job.submit.latency").timer()).isNotNull();
    assertThat(registry.find("cae.mq.consume.failure").counter()).isNotNull();
    String scrape =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/actuator/prometheus"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String name :
        java.util.List.of(
            "cae_jobs_total",
            "cae_agent_offline_total",
            "cae_resource_reservation_total",
            "cae_reports_generated_total",
            "cae_node_cpu_total")) assertThat(scrape).contains("# TYPE " + name + " gauge");
    assertThat(scrape)
        .contains("cae_jobs_total{analysis_type=\"STRUCTURE\",status=\"POST_PROCESSING\",");
  }
}
