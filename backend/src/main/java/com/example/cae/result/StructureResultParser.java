package com.example.cae.result;

import com.example.cae.job.domain.AnalysisType;
import java.util.ArrayList;
import java.util.List;

public class StructureResultParser extends AbstractResultParser {
  public StructureResultParser(String version) {
    super(version, AnalysisType.STRUCTURE, "MOCK_OPTISTRUCT");
  }

  @Override
  public ParseResult parse(ParseContext context) {
    validate(context);
    var raw = context.raw();
    List<CanonicalMetric> metrics = new ArrayList<>();
    metrics.add(metric(context, "MASS", "Mass", raw.get("mass_kg"), "kg", "MASS", true));
    metrics.add(
        metric(
            context,
            "TORSIONAL_STIFFNESS",
            "Torsional stiffness",
            raw.get("torsional_stiffness_nm_per_deg"),
            "N*m/deg",
            "TORSIONAL_STIFFNESS",
            true));
    metrics.add(
        metric(
            context,
            "BENDING_STIFFNESS",
            "Bending stiffness",
            raw.get("bending_stiffness_n_per_mm"),
            "N/mm",
            "BENDING_STIFFNESS",
            true));
    if (!(raw.get("modes_hz") instanceof List<?> modes) || modes.size() != 4)
      throw new IllegalArgumentException("Exactly four mode frequencies are required");
    double previous = 0;
    for (int i = 0; i < modes.size(); i++) {
      CanonicalMetric metric =
          metric(
              context,
              "MODE_" + (i + 1) + "_FREQUENCY",
              "Mode " + (i + 1) + " frequency",
              modes.get(i),
              "Hz",
              "FREQUENCY",
              true);
      // v2 adds a physically useful ordering check while retaining the original canonical values.
      if (version().equals("v2") && metric.valueNum().doubleValue() <= previous)
        throw new IllegalArgumentException(
            "Parser v2 requires strictly increasing mode frequencies");
      previous = metric.valueNum().doubleValue();
      metrics.add(metric);
    }
    return new ParseResult(metrics, "COMPLETE");
  }
}
