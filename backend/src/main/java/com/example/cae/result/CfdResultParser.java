package com.example.cae.result;

import com.example.cae.job.domain.AnalysisType;
import java.util.List;

public class CfdResultParser extends AbstractResultParser {
  public CfdResultParser(String version) {
    super(version, AnalysisType.CFD, "MOCK_CFD");
  }

  @Override
  public ParseResult parse(ParseContext context) {
    validate(context);
    if (!(context.raw().get("converged") instanceof Boolean converged))
      throw new IllegalArgumentException("CFD convergence must be a boolean");
    CanonicalMetric residual =
        metric(
            context,
            "CFD_RESIDUAL_FINAL",
            "Final residual",
            context.raw().get("residual_final"),
            "1",
            "RESIDUAL",
            true);
    if (version().equals("v2") && converged && residual.valueNum().doubleValue() > 0.001)
      throw new IllegalArgumentException(
          "Parser v2 rejects convergence inconsistent with residual");
    return new ParseResult(
        List.of(
            metric(
                context,
                "CFD_CD",
                "Drag coefficient",
                context.raw().get("cd"),
                "1",
                "COEFFICIENT",
                true),
            metric(
                context,
                "CFD_CL",
                "Lift coefficient",
                context.raw().get("cl"),
                "1",
                "COEFFICIENT",
                false),
            residual,
            new CanonicalMetric(
                "CFD_CONVERGED",
                "Converged",
                null,
                converged.toString(),
                "1",
                "BOOLEAN",
                converged ? "VALID" : "REVIEW_REQUIRED",
                context.job().get("conditionCode").toString(),
                context.subcase())),
        "PILOT");
  }
}
