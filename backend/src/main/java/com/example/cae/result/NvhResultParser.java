package com.example.cae.result;

import com.example.cae.job.domain.AnalysisType;
import java.util.List;

public class NvhResultParser extends AbstractResultParser {
  public NvhResultParser(String version) {
    super(version, AnalysisType.NVH, "MOCK_NVH");
  }

  @Override
  public ParseResult parse(ParseContext context) {
    validate(context);
    return new ParseResult(
        List.of(
            metric(
                context,
                "NVH_PEAK_FREQUENCY",
                "Peak frequency",
                context.raw().get("peak_frequency_hz"),
                "Hz",
                "FREQUENCY",
                true),
            metric(
                context,
                "NVH_PEAK_RESPONSE",
                "Peak response",
                context.raw().get("peak_response_db"),
                "dB",
                "SOUND_PRESSURE_LEVEL",
                false)),
        "PILOT");
  }
}
