package com.example.cae.batch;

import com.example.cae.job.api.CreateJobRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record BatchRequest(
    @NotBlank @Size(max = 128) String name,
    @NotNull Long projectId,
    @NotEmpty @Size(max = 200) List<@Valid @NotNull CreateJobRequest> jobs,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String idempotencyKey) {}
