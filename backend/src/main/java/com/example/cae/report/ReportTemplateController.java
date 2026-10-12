package com.example.cae.report;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportTemplateController {
  private final ReportTemplateService templates;

  public ReportTemplateController(ReportTemplateService templates) {
    this.templates = templates;
  }

  public record Enabled(boolean enabled) {}

  @GetMapping("/api/v1/report-templates")
  public List<Map<String, Object>> templates() {
    return templates.templates();
  }

  @PatchMapping("/api/v1/report-templates/{code}")
  public List<Map<String, Object>> enabled(
      Authentication user, @PathVariable String code, @RequestBody Enabled body) {
    return templates.enabled(user, code, body.enabled());
  }
}
