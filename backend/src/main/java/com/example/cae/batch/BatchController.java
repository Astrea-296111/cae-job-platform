package com.example.cae.batch;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {
  private final BatchService batches;

  public BatchController(BatchService batches) {
    this.batches = batches;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> create(Authentication user, @Valid @RequestBody BatchRequest request) {
    return batches.create(user, request);
  }

  @GetMapping
  public List<Map<String, Object>> list(
      Authentication user, @RequestParam(required = false) Long projectId) {
    return batches.list(user, projectId);
  }

  @GetMapping("/{batchNo}")
  public Map<String, Object> detail(Authentication user, @PathVariable String batchNo) {
    return batches.detail(user, batchNo);
  }
}
