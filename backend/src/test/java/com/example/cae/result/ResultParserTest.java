package com.example.cae.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.cae.job.domain.AnalysisType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResultParserTest {
  private static final String CONDITION = "ENGINEERING_CONDITION_7";
  private static final int SUBCASE = 7;
  private final ResultParserRegistry registry = new ResultParserRegistry();

  @ParameterizedTest
  @MethodSource("disciplines")
  void parsesActualSolverSchemaWithoutChangingValuesUnitsOrEngineeringContext(
      AnalysisType analysis) {
    ParseContext context = context(analysis);
    ParseResult result = parser(analysis, "v1").parse(context);
    Map<String, CanonicalMetric> actual =
        result.metrics().stream()
            .filter(metric -> metric.valueNum() != null)
            .collect(Collectors.toMap(CanonicalMetric::metricCode, Function.identity()));
    Map<String, ExpectedMetric> expected = expectedMetrics(analysis);

    assertEquals(expected.keySet(), actual.keySet(), "all canonical metrics must be present once");
    assertEquals(expected.size() + (analysis == AnalysisType.CFD ? 1 : 0), result.metrics().size());
    assertEquals(analysis == AnalysisType.STRUCTURE ? "COMPLETE" : "PILOT", result.maturity());
    expected.forEach(
        (code, value) -> {
          CanonicalMetric metric = actual.get(code);
          assertEquals(
              0, value.value().compareTo(metric.valueNum()), code + " must preserve raw value");
          assertNull(metric.valueText(), code + " is a numeric metric");
          assertEquals(value.unit(), metric.unit(), code + " unit");
          assertEquals(value.dimension(), metric.dimension(), code + " physical dimension");
          assertTrue(metric.metricName() != null && !metric.metricName().isBlank());
          assertEquals("VALID", metric.status());
          assertEquals(CONDITION, metric.conditionCode());
          assertEquals(SUBCASE, metric.subcase());
        });
  }

  @Test
  void cfdConvergenceIsASeparateTypedBooleanRatherThanAFabricatedNumericMetric() {
    ParseResult result = parser(AnalysisType.CFD, "v1").parse(context(AnalysisType.CFD));
    CanonicalMetric metric =
        result.metrics().stream()
            .filter(value -> value.metricCode().equals("CFD_CONVERGED"))
            .findFirst()
            .orElseThrow();
    assertNull(metric.valueNum());
    assertEquals("true", metric.valueText());
    assertEquals("1", metric.unit());
    assertEquals("BOOLEAN", metric.dimension());
    assertEquals("VALID", metric.status());
    assertEquals(CONDITION, metric.conditionCode());
    assertEquals(SUBCASE, metric.subcase());
  }

  @ParameterizedTest
  @MethodSource("disciplines")
  void supportsOnlyTheMatchingDisciplineAndWhitelistedSolver(AnalysisType analysis) {
    ResultParser parser = parser(analysis, "v1");
    for (AnalysisType candidate : AnalysisType.values()) {
      assertEquals(candidate == analysis, parser.supports(candidate, solver(analysis)));
    }
    assertFalse(parser.supports(analysis, "OPTISTRUCT"));
    assertFalse(parser.supports(analysis, "UNKNOWN_SOLVER"));
    assertFalse(parser.supports(analysis, null));
  }

  @ParameterizedTest
  @MethodSource("disciplines")
  void parserVersionsAreIndependentStrategiesThatRetainCanonicalValues(AnalysisType analysis) {
    ResultParser first = parser(analysis, "v1");
    ResultParser second = parser(analysis, "v2");
    assertNotSame(first, second);
    assertEquals("v1", first.version());
    assertEquals("v2", second.version());
    assertEquals(first.parse(context(analysis)), second.parse(context(analysis)));
    assertEquals(
        "v1", first.version(), "selecting v2 must not mutate the previously selected parser");
  }

  @ParameterizedTest
  @CsvSource({
    "STRUCTURE, MOCK_NVH, v1", "NVH, MOCK_OPTISTRUCT, v1",
    "CRASH, MOCK_CFD, v1", "CFD, MOCK_CRASH, v1",
    "STRUCTURE, OPTISTRUCT, v1", "STRUCTURE, MOCK_OPTISTRUCT, v3"
  })
  void registryRejectsUnsupportedAnalysisSolverAndVersion(
      AnalysisType analysis, String solver, String version) {
    assertThrows(IllegalArgumentException.class, () -> registry.select(analysis, solver, version));
  }

  @ParameterizedTest
  @MethodSource("disciplines")
  void everyParserRejectsAnUnsupportedRawSchema(AnalysisType analysis) {
    ParseContext context = context(analysis);
    context.raw().put("schemaVersion", 2);
    assertThrows(IllegalArgumentException.class, () -> parser(analysis, "v1").parse(context));
  }

  @ParameterizedTest
  @MethodSource("invalidIdentityFields")
  void rejectsRawResultsFromAnotherAnalysisConditionProfileOrSubcase(String field, Object value) {
    ParseContext context = context(AnalysisType.STRUCTURE);
    context.raw().put(field, value);
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.STRUCTURE, "v1").parse(context));
  }

  @Test
  void rejectsAJobThatDoesNotUseTheParsersSupportedSolver() {
    ParseContext context = context(AnalysisType.STRUCTURE);
    context.job().put("solverType", "MOCK_NVH");
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.STRUCTURE, "v1").parse(context));
  }

  @ParameterizedTest
  @MethodSource("numericFields")
  void missingNumericOutputNeverBecomesAnInventedZero(AnalysisType analysis, String field) {
    ParseContext context = context(analysis);
    context.raw().remove(field);
    assertThrows(IllegalArgumentException.class, () -> parser(analysis, "v1").parse(context));
  }

  @ParameterizedTest
  @MethodSource("invalidNumericValues")
  void rejectsNonFiniteNumbersAndBooleanOrStringCoercion(
      AnalysisType analysis, String field, Object invalid) {
    ParseContext context = context(analysis);
    context.raw().put(field, invalid);
    assertThrows(IllegalArgumentException.class, () -> parser(analysis, "v1").parse(context));
  }

  @ParameterizedTest
  @CsvSource({"v1, 1e100", "v2, 1e100", "v1, 1e20", "v2, 0.12345678901"})
  void rejectsFiniteNumbersOutsideTheDurableDecimalStorageRange(String version, String value) {
    ParseContext context = context(AnalysisType.STRUCTURE);
    context.raw().put("mass_kg", new BigDecimal(value));
    assertThrows(
        IllegalArgumentException.class,
        () -> parser(AnalysisType.STRUCTURE, version).parse(context));
  }

  @Test
  void preservesAValueAtTheExactTwentyIntegerAndTenFractionDigitStorageBoundary() {
    ParseContext context = context(AnalysisType.STRUCTURE);
    BigDecimal boundary = new BigDecimal("99999999999999999999.1234567891");
    context.raw().put("mass_kg", boundary);
    CanonicalMetric mass =
        parser(AnalysisType.STRUCTURE, "v1").parse(context).metrics().stream()
            .filter(m -> m.metricCode().equals("MASS"))
            .findFirst()
            .orElseThrow();
    assertEquals(0, boundary.compareTo(mass.valueNum()));
  }

  @ParameterizedTest
  @MethodSource("invalidModeLists")
  void structureRequiresExactlyFourFinitePositiveNumericModeFrequencies(Object modes) {
    ParseContext context = context(AnalysisType.STRUCTURE);
    context.raw().put("modes_hz", modes);
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.STRUCTURE, "v1").parse(context));
  }

  @ParameterizedTest
  @CsvSource({
    "STRUCTURE, mass_kg",
    "STRUCTURE, torsional_stiffness_nm_per_deg",
    "STRUCTURE, bending_stiffness_n_per_mm",
    "NVH, peak_frequency_hz",
    "CRASH, max_intrusion_mm",
    "CRASH, peak_acceleration_g",
    "CRASH, energy_balance_ratio",
    "CFD, cd",
    "CFD, residual_final"
  })
  void physicallyPositiveMetricsRejectZeroAndNegativeValues(AnalysisType analysis, String field) {
    for (int invalid : List.of(0, -1)) {
      ParseContext context = context(analysis);
      context.raw().put(field, invalid);
      assertThrows(IllegalArgumentException.class, () -> parser(analysis, "v1").parse(context));
    }
  }

  @Test
  void signedLiftCoefficientAndDecibelsRetainTheirNegativeValues() {
    ParseContext cfd = context(AnalysisType.CFD);
    cfd.raw().put("cl", -0.0345);
    CanonicalMetric lift =
        parser(AnalysisType.CFD, "v1").parse(cfd).metrics().stream()
            .filter(value -> value.metricCode().equals("CFD_CL"))
            .findFirst()
            .orElseThrow();
    assertEquals(0, new BigDecimal("-0.0345").compareTo(lift.valueNum()));
    ParseContext nvh = context(AnalysisType.NVH);
    nvh.raw().put("peak_response_db", -3.2);
    CanonicalMetric response =
        parser(AnalysisType.NVH, "v1").parse(nvh).metrics().stream()
            .filter(value -> value.metricCode().equals("NVH_PEAK_RESPONSE"))
            .findFirst()
            .orElseThrow();
    assertEquals(0, new BigDecimal("-3.2").compareTo(response.valueNum()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "false", "1"})
  void cfdConvergenceRejectsStringsAndNumericBooleanSubstitutes(String invalid) {
    ParseContext context = context(AnalysisType.CFD);
    context.raw().put("converged", invalid);
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.CFD, "v1").parse(context));
    context.raw().put("converged", 1);
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.CFD, "v1").parse(context));
  }

  @Test
  void missingModesOrConvergenceCannotProduceAnIncompleteSuccessfulParse() {
    ParseContext structure = context(AnalysisType.STRUCTURE);
    structure.raw().remove("modes_hz");
    assertThrows(
        IllegalArgumentException.class,
        () -> parser(AnalysisType.STRUCTURE, "v1").parse(structure));
    ParseContext cfd = context(AnalysisType.CFD);
    cfd.raw().remove("converged");
    assertThrows(IllegalArgumentException.class, () -> parser(AnalysisType.CFD, "v1").parse(cfd));
  }

  @Test
  void unconvergedCfdRetainsMetricsWithAnExplicitReviewRequirement() {
    ParseContext context = context(AnalysisType.CFD);
    context.raw().put("converged", false);
    ParseResult result = parser(AnalysisType.CFD, "v2").parse(context);
    assertEquals(4, result.metrics().size());
    CanonicalMetric convergence =
        result.metrics().stream()
            .filter(value -> value.metricCode().equals("CFD_CONVERGED"))
            .findFirst()
            .orElseThrow();
    assertEquals("false", convergence.valueText());
    assertNull(convergence.valueNum());
    assertEquals("REVIEW_REQUIRED", convergence.status());
    assertEquals("PILOT", result.maturity());
  }

  @Test
  void structureV2ChecksModeOrderingWithoutSilentlyReorderingTheV1Result() {
    ParseContext context = context(AnalysisType.STRUCTURE);
    context.raw().put("modes_hz", List.of(31.9, 28.6, 42.3, 49.8));
    ParseResult v1 = parser(AnalysisType.STRUCTURE, "v1").parse(context);
    assertEquals(0, new BigDecimal("31.9").compareTo(v1.metrics().get(3).valueNum()));
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.STRUCTURE, "v2").parse(context));
    context.raw().put("modes_hz", List.of(28.6, 28.6, 42.3, 49.8));
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.STRUCTURE, "v2").parse(context));
  }

  @Test
  void crashV2DetectsInconsistentEnergyBalanceInsteadOfClampingRawValues() {
    ParseContext context = context(AnalysisType.CRASH);
    context.raw().put("energy_balance_ratio", 1.2);
    CanonicalMetric energy =
        parser(AnalysisType.CRASH, "v1").parse(context).metrics().stream()
            .filter(value -> value.metricCode().equals("CRASH_ENERGY_BALANCE_RATIO"))
            .findFirst()
            .orElseThrow();
    assertEquals(0, new BigDecimal("1.2").compareTo(energy.valueNum()));
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.CRASH, "v2").parse(context));
  }

  @Test
  void cfdV2ChecksClaimedConvergenceAgainstTheActualResidual() {
    ParseContext context = context(AnalysisType.CFD);
    context.raw().put("residual_final", 0.01);
    assertEquals(4, parser(AnalysisType.CFD, "v1").parse(context).metrics().size());
    assertThrows(
        IllegalArgumentException.class, () -> parser(AnalysisType.CFD, "v2").parse(context));
    context.raw().put("converged", false);
    assertEquals(
        "REVIEW_REQUIRED", parser(AnalysisType.CFD, "v2").parse(context).metrics().get(3).status());
  }

  private ResultParser parser(AnalysisType analysis, String version) {
    return registry.select(analysis, solver(analysis), version);
  }

  static Stream<AnalysisType> disciplines() {
    return Stream.of(AnalysisType.values());
  }

  static Stream<Arguments> invalidIdentityFields() {
    return Stream.of(
        Arguments.of("analysisType", "NVH"),
        Arguments.of("conditionCode", "DIFFERENT_CONDITION"),
        Arguments.of("solverProfile", "DIFFERENT_PROFILE"),
        Arguments.of("subcase", SUBCASE + 1),
        Arguments.of("subcase", 7.5),
        Arguments.of("subcase", "7"),
        Arguments.of("subcase", true),
        Arguments.of("schemaVersion", 1.5),
        Arguments.of("schemaVersion", "1"),
        Arguments.of("schemaVersion", true));
  }

  static Stream<Arguments> numericFields() {
    return Stream.of(
        Arguments.of(AnalysisType.STRUCTURE, "mass_kg"),
        Arguments.of(AnalysisType.STRUCTURE, "torsional_stiffness_nm_per_deg"),
        Arguments.of(AnalysisType.STRUCTURE, "bending_stiffness_n_per_mm"),
        Arguments.of(AnalysisType.NVH, "peak_frequency_hz"),
        Arguments.of(AnalysisType.NVH, "peak_response_db"),
        Arguments.of(AnalysisType.CRASH, "max_intrusion_mm"),
        Arguments.of(AnalysisType.CRASH, "peak_acceleration_g"),
        Arguments.of(AnalysisType.CRASH, "energy_balance_ratio"),
        Arguments.of(AnalysisType.CFD, "cd"),
        Arguments.of(AnalysisType.CFD, "cl"),
        Arguments.of(AnalysisType.CFD, "residual_final"));
  }

  static Stream<Arguments> invalidNumericValues() {
    return disciplines()
        .flatMap(
            analysis ->
                Stream.of(
                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        true,
                        "310.125",
                        new Object())
                    .map(value -> Arguments.of(analysis, primaryNumericField(analysis), value)));
  }

  static Stream<Arguments> invalidModeLists() {
    List<Object> missing = new ArrayList<>(List.of(28.6, 31.9, 42.3, 49.8));
    missing.set(2, null);
    return Stream.of(
        Arguments.of(List.of(28.6, 31.9, 42.3)),
        Arguments.of(List.of(28.6, 31.9, 42.3, 49.8, 58.1)),
        Arguments.of("28.6,31.9,42.3,49.8"),
        Arguments.of(List.of(28.6, "31.9", 42.3, 49.8)),
        Arguments.of(List.of(28.6, true, 42.3, 49.8)),
        Arguments.of(List.of(28.6, Double.NaN, 42.3, 49.8)),
        Arguments.of(List.of(28.6, Double.POSITIVE_INFINITY, 42.3, 49.8)),
        Arguments.of(List.of(28.6, 0, 42.3, 49.8)),
        Arguments.of(List.of(28.6, -1, 42.3, 49.8)),
        Arguments.of(missing));
  }

  private static String solver(AnalysisType analysis) {
    return switch (analysis) {
      case STRUCTURE -> "MOCK_OPTISTRUCT";
      case NVH -> "MOCK_NVH";
      case CRASH -> "MOCK_CRASH";
      case CFD -> "MOCK_CFD";
    };
  }

  private static String primaryNumericField(AnalysisType analysis) {
    return switch (analysis) {
      case STRUCTURE -> "mass_kg";
      case NVH -> "peak_frequency_hz";
      case CRASH -> "max_intrusion_mm";
      case CFD -> "cd";
    };
  }

  private static ParseContext context(AnalysisType analysis) {
    Map<String, Object> parameters = Map.of("subcase", SUBCASE, "seed", 42, "thicknessScale", 1.0);
    Map<String, Object> job =
        new HashMap<>(
            Map.of(
                "id",
                71L,
                "jobNo",
                "CAE-PARSER-UNIT-71",
                "analysisType",
                analysis.name(),
                "solverType",
                solver(analysis),
                "solverProfile",
                "STANDARD",
                "conditionCode",
                CONDITION));
    Map<String, Object> raw =
        new HashMap<>(
            Map.of(
                "schemaVersion",
                1,
                "analysisType",
                analysis.name(),
                "conditionCode",
                CONDITION,
                "subcase",
                SUBCASE,
                "solverProfile",
                "STANDARD",
                "seed",
                42,
                "parameters",
                parameters));
    switch (analysis) {
      case STRUCTURE ->
          raw.putAll(
              Map.of(
                  "mass_kg",
                  310.125,
                  "torsional_stiffness_nm_per_deg",
                  21001.234,
                  "bending_stiffness_n_per_mm",
                  18502.345,
                  "modes_hz",
                  List.of(28.6, 31.9, 42.3, 49.8)));
      case NVH -> raw.putAll(Map.of("peak_frequency_hz", 87.125, "peak_response_db", 61.25));
      case CRASH ->
          raw.putAll(
              Map.of(
                  "max_intrusion_mm",
                  139.125,
                  "peak_acceleration_g",
                  35.5,
                  "energy_balance_ratio",
                  0.9812));
      case CFD ->
          raw.putAll(
              Map.of("cd", 0.27812, "cl", 0.04512, "residual_final", 1e-6, "converged", true));
    }
    Map<String, Object> execution =
        Map.of(
            "id",
            81L,
            "jobId",
            71L,
            "status",
            "SUCCESS",
            "submitRequestId",
            "11111111-1111-1111-1111-111111111111");
    Map<String, Object> manifest =
        Map.of(
            "analysisType", analysis.name(), "conditionCode", CONDITION, "parameters", parameters);
    return new ParseContext(job, execution, raw, manifest, "a".repeat(64), SUBCASE);
  }

  private static Map<String, ExpectedMetric> expectedMetrics(AnalysisType analysis) {
    return switch (analysis) {
      case STRUCTURE ->
          Map.of(
              "MASS", expected("310.125", "kg", "MASS"),
              "TORSIONAL_STIFFNESS", expected("21001.234", "N*m/deg", "TORSIONAL_STIFFNESS"),
              "BENDING_STIFFNESS", expected("18502.345", "N/mm", "BENDING_STIFFNESS"),
              "MODE_1_FREQUENCY", expected("28.6", "Hz", "FREQUENCY"),
              "MODE_2_FREQUENCY", expected("31.9", "Hz", "FREQUENCY"),
              "MODE_3_FREQUENCY", expected("42.3", "Hz", "FREQUENCY"),
              "MODE_4_FREQUENCY", expected("49.8", "Hz", "FREQUENCY"));
      case NVH ->
          Map.of(
              "NVH_PEAK_FREQUENCY", expected("87.125", "Hz", "FREQUENCY"),
              "NVH_PEAK_RESPONSE", expected("61.25", "dB", "SOUND_PRESSURE_LEVEL"));
      case CRASH ->
          Map.of(
              "CRASH_INTRUSION_MAX", expected("139.125", "mm", "LENGTH"),
              "CRASH_PEAK_ACCELERATION", expected("35.5", "g", "ACCELERATION"),
              "CRASH_ENERGY_BALANCE_RATIO", expected("0.9812", "1", "RATIO"));
      case CFD ->
          Map.of(
              "CFD_CD", expected("0.27812", "1", "COEFFICIENT"),
              "CFD_CL", expected("0.04512", "1", "COEFFICIENT"),
              "CFD_RESIDUAL_FINAL", expected("0.000001", "1", "RESIDUAL"));
    };
  }

  private static ExpectedMetric expected(String value, String unit, String dimension) {
    return new ExpectedMetric(new BigDecimal(value), unit, dimension);
  }

  private record ExpectedMetric(BigDecimal value, String unit, String dimension) {}
}
