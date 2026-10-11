package com.example.cae.job.submission;

import com.example.cae.common.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Small public simulation controls, never executable commands. */
public final class MockExecutionOptions {
  private MockExecutionOptions() {}

  public static Map<String, Object> validate(Map<String, Object> requested) {
    Map<String, Object> options =
        new LinkedHashMap<>(
            Map.of(
                "runtimeSeconds",
                3,
                "successProbability",
                1,
                "failureMode",
                "NONE",
                "progressInterval",
                0.2,
                "responseDelayOnceSeconds",
                0));
    if (requested != null) {
      for (String key : requested.keySet())
        if (!Set.of(
                "runtimeSeconds",
                "successProbability",
                "failureMode",
                "progressInterval",
                "responseDelayOnceSeconds",
                "seed")
            .contains(key)) throw ApiException.invalid("Unsupported mock execution option: " + key);
      options.putAll(requested);
    }
    number(options, "runtimeSeconds", 0.05, 600);
    number(options, "successProbability", 0, 1);
    number(options, "progressInterval", 0.02, 10);
    number(options, "responseDelayOnceSeconds", 0, 30);
    if (!(options.get("failureMode") instanceof String mode)
        || !Set.of("NONE", "RUNTIME_FAILED", "TIMEOUT").contains(mode))
      throw ApiException.invalid("Unknown mock solver failure mode");
    if (options.get("seed") != null) {
      number(options, "seed", 0, 4294967295L);
      Number seed = (Number) options.get("seed");
      if (seed.doubleValue() != seed.longValue())
        throw ApiException.invalid("Mock seed must be an integer");
    }
    return options;
  }

  private static void number(
      Map<String, Object> value, String key, double minimum, double maximum) {
    if (!(value.get(key) instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() < minimum
        || n.doubleValue() > maximum) throw ApiException.invalid("Invalid mock option: " + key);
  }
}
