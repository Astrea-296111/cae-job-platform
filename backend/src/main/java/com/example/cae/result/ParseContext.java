package com.example.cae.result;

import java.util.Map;

public record ParseContext(
    Map<String, Object> job,
    Map<String, Object> execution,
    Map<String, Object> raw,
    Map<String, Object> manifest,
    String rawChecksum,
    int subcase) {}
