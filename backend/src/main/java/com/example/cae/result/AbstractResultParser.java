package com.example.cae.result;

import com.example.cae.job.domain.AnalysisType;
import java.math.BigDecimal;
import java.util.Map;

abstract class AbstractResultParser implements ResultParser {
  private final String version;
  private final AnalysisType analysis;
  private final String solver;

  AbstractResultParser(String version, AnalysisType analysis, String solver) {
    if (!version.equals("v1") && !version.equals("v2"))
      throw new IllegalArgumentException("Unsupported parser version");
    this.version = version;
    this.analysis = analysis;
    this.solver = solver;
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public boolean supports(AnalysisType analysisType, String solverType) {
    return analysis == analysisType && solver.equals(solverType);
  }

  protected void validate(ParseContext context) {
    Map<String, Object> raw = context.raw();
    if (!supports(
            AnalysisType.valueOf(context.job().get("analysisType").toString()),
            context.job().get("solverType").toString())
        || !analysis.name().equals(raw.get("analysisType"))
        || !context.job().get("conditionCode").equals(raw.get("conditionCode"))
        || !context.job().get("solverProfile").equals(raw.get("solverProfile"))
        || integer(raw.get("schemaVersion"), "schemaVersion") != 1
        || integer(raw.get("subcase"), "subcase") != context.subcase())
      throw new IllegalArgumentException(
          "Raw result analysis, condition, subcase or schema mismatch");
  }

  static int integer(Object value, String name) {
    if (!(value instanceof Number number)
        || !Double.isFinite(number.doubleValue())
        || number.doubleValue() != number.longValue()
        || number.longValue() < Integer.MIN_VALUE
        || number.longValue() > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Expected integral " + name);
    return number.intValue();
  }

  protected CanonicalMetric metric(
      ParseContext context,
      String code,
      String name,
      Object raw,
      String unit,
      String dimension,
      boolean positive) {
    if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()))
      throw new IllegalArgumentException("Missing or non-finite numeric metric " + code);
    BigDecimal value = new BigDecimal(number.toString()).stripTrailingZeros();
    if (positive && value.signum() <= 0)
      throw new IllegalArgumentException("Expected positive engineering metric " + code);
    return new CanonicalMetric(
        code,
        name,
        value,
        null,
        unit,
        dimension,
        "VALID",
        context.job().get("conditionCode").toString(),
        context.subcase());
  }
}
