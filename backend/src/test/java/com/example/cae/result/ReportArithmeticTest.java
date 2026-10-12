package com.example.cae.result;

import static org.assertj.core.api.Assertions.*;

import com.example.cae.common.JsonCodec;
import com.example.cae.report.BaselineComparisonService;
import com.example.cae.report.RenderFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportArithmeticTest {
  private Map<String, Object> metric(String value) {
    return Map.of(
        "metricCode",
        "TORSIONAL_STIFFNESS",
        "unit",
        "N*m/deg",
        "dimension",
        "TORSIONAL_STIFFNESS",
        "valueNum",
        value);
  }

  @Test
  void exactDeltaAndExplicitRoundingAreCalculatedInJava() {
    var row =
        new BaselineComparisonService()
            .compare(List.of(metric("21540.2")), List.of(metric("20850")))
            .get(0);
    assertThat(row)
        .containsEntry("delta", "690.2")
        .containsEntry("deltaPercent", "3.310312")
        .containsEntry("status", "MATCHED");
  }

  @Test
  void zeroMissingAndLargeSignedBaselinesRemainTypedWithoutOverflow() {
    var service = new BaselineComparisonService();
    assertThat(service.compare(List.of(metric("2")), List.of(metric("0"))).get(0))
        .containsEntry("delta", "2")
        .containsEntry("deltaPercent", null)
        .containsEntry("status", "ZERO_BASELINE");
    assertThat(service.compare(List.of(metric("2")), List.of()).get(0))
        .containsEntry("baseline", null)
        .containsEntry("delta", null)
        .containsEntry("status", "NO_BASELINE");
    assertThat(
            service
                .compare(
                    List.of(metric("99999999999999999999")),
                    List.of(metric("-99999999999999999999")))
                .get(0))
        .containsEntry("delta", "199999999999999999998")
        .containsEntry("deltaPercent", "-200");
  }

  @Test
  void canonicalFingerprintPreservesUnicodeNullListsAndDecimalStrings() throws Exception {
    var json = new JsonCodec(new ObjectMapper());
    var fixture =
        json.readMap(Files.readString(Path.of("../docs/contracts/render-fingerprint.json")));
    assertThat(new RenderFingerprint(json).of(json.readMap(fixture.get("payload"))))
        .isEqualTo(fixture.get("sha256"));
  }
}
