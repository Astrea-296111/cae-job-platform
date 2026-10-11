package com.example.cae.result;

import java.math.BigDecimal;

/** A typed value with its engineering condition; a boolean is never converted to a number. */
public record CanonicalMetric(
    String metricCode,
    String metricName,
    BigDecimal valueNum,
    String valueText,
    String unit,
    String dimension,
    String status,
    String conditionCode,
    int subcase) {
  public CanonicalMetric {
    if (metricCode == null
        || !metricCode.matches("[A-Z][A-Z0-9_]{0,63}")
        || metricName == null
        || metricName.isBlank()
        || (valueNum == null) == (valueText == null)
        || unit == null
        || dimension == null
        || status == null
        || conditionCode == null
        || !conditionCode.matches("[A-Za-z0-9_-]{1,64}")
        || subcase < 1
        || subcase > 1000)
      throw new IllegalArgumentException("Invalid canonical engineering metric");
    if (valueNum != null && (valueNum.precision() - valueNum.scale() > 20 || valueNum.scale() > 10))
      throw new IllegalArgumentException("Canonical numeric value exceeds the storage precision");
  }
}
