package com.example.cae.result;

import com.example.cae.job.domain.AnalysisType;

public interface ResultParser {
  boolean supports(AnalysisType analysisType, String solverType);

  ParseResult parse(ParseContext context);

  String version();
}
