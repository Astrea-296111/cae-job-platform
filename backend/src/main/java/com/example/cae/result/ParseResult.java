package com.example.cae.result;

import java.util.List;

public record ParseResult(List<CanonicalMetric> metrics, String maturity) {
  public ParseResult {
    metrics = List.copyOf(metrics);
  }
}
