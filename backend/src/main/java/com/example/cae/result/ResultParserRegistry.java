package com.example.cae.result;

import com.example.cae.job.domain.AnalysisType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ResultParserRegistry {
  private final List<ResultParser> parsers;

  public ResultParserRegistry() {
    List<ResultParser> registered = new ArrayList<>();
    for (String version : List.of("v1", "v2")) {
      registered.add(new StructureResultParser(version));
      registered.add(new NvhResultParser(version));
      registered.add(new CrashResultParser(version));
      registered.add(new CfdResultParser(version));
    }
    parsers = List.copyOf(registered);
  }

  public ResultParser select(AnalysisType type, String solver, String version) {
    validateVersion(version);
    return parsers.stream()
        .filter(parser -> parser.version().equals(version) && parser.supports(type, solver))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("No parser supports this solver and analysis"));
  }

  public static void validateVersion(String version) {
    if (!"v1".equals(version) && !"v2".equals(version))
      throw new IllegalArgumentException("Supported parser versions are v1 and v2");
  }
}
