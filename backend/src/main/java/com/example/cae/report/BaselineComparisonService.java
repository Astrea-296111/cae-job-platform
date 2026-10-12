package com.example.cae.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** All engineering arithmetic belongs to the control plane, never a report template. */
@Service
public class BaselineComparisonService {
  public List<Map<String, Object>> compare(
      List<Map<String, Object>> current, List<Map<String, Object>> baseline) {
    Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
    baseline.forEach(row -> indexed.put(row.get("metricCode").toString(), row));
    List<Map<String, Object>> comparisons = new ArrayList<>();
    for (Map<String, Object> metric : current) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("metricCode", metric.get("metricCode"));
      row.put("unit", metric.get("unit"));
      BigDecimal now = decimal(metric.get("valueNum"));
      Map<String, Object> old = indexed.get(metric.get("metricCode"));
      BigDecimal before = old == null ? null : decimal(old.get("valueNum"));
      boolean compatible =
          old != null
              && metric.get("unit").equals(old.get("unit"))
              && metric.get("dimension").equals(old.get("dimension"));
      row.put("current", plain(now));
      row.put("baseline", compatible ? plain(before) : null);
      row.put("delta", null);
      row.put("deltaPercent", null);
      if (now == null) row.put("status", "TEXT");
      else if (old == null) row.put("status", baseline.isEmpty() ? "NO_BASELINE" : "MISSING");
      else if (!compatible || before == null) row.put("status", "MISSING");
      else {
        BigDecimal delta = now.subtract(before);
        row.put("delta", plain(delta));
        row.put("status", before.signum() == 0 ? "ZERO_BASELINE" : "MATCHED");
        if (before.signum() != 0)
          row.put(
              "deltaPercent",
              plain(
                  delta.multiply(BigDecimal.valueOf(100)).divide(before, 6, RoundingMode.HALF_UP)));
      }
      comparisons.add(row);
    }
    return comparisons;
  }

  private static BigDecimal decimal(Object value) {
    return value == null ? null : new BigDecimal(value.toString());
  }

  public static String plain(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }
}
