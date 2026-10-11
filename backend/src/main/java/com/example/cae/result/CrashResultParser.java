package com.example.cae.result;

import com.example.cae.job.domain.AnalysisType;
import java.util.List;

public class CrashResultParser extends AbstractResultParser {
  public CrashResultParser(String version) {
    super(version, AnalysisType.CRASH, "MOCK_CRASH");
  }

  @Override
  public ParseResult parse(ParseContext context) {
    validate(context);
    CanonicalMetric energy =
        metric(
            context,
            "CRASH_ENERGY_BALANCE_RATIO",
            "Energy balance ratio",
            context.raw().get("energy_balance_ratio"),
            "1",
            "RATIO",
            true);
    if (version().equals("v2") && energy.valueNum().doubleValue() > 1.1)
      throw new IllegalArgumentException("Parser v2 rejects an inconsistent crash energy balance");
    return new ParseResult(
        List.of(
            metric(
                context,
                "CRASH_INTRUSION_MAX",
                "Maximum intrusion",
                context.raw().get("max_intrusion_mm"),
                "mm",
                "LENGTH",
                true),
            metric(
                context,
                "CRASH_PEAK_ACCELERATION",
                "Peak acceleration",
                context.raw().get("peak_acceleration_g"),
                "g",
                "ACCELERATION",
                true),
            energy),
        "PILOT");
  }
}
