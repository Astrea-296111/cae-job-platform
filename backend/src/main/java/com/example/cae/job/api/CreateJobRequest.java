package com.example.cae.job.api;

import com.example.cae.job.domain.AnalysisType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateJobRequest(
    @NotNull Long projectId,
    @NotNull Long modelRevisionId,
    @NotNull AnalysisType analysisType,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String conditionCode,
    @Size(max = 40) String solverType,
    @Size(max = 64) String solverProfile,
    @Min(0) @Max(100) Integer priority,
    @Size(max = 500) String casePath,
    @Size(max = 32) String resourceProfile,
    Map<String, Object> input,
    Map<String, Object> resource,
    Map<String, Object> mock) {}
