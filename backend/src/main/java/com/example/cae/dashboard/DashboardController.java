package com.example.cae.dashboard;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class DashboardController {
  private final DashboardService dashboard;

  public DashboardController(DashboardService dashboard) {
    this.dashboard = dashboard;
  }

  @GetMapping("/api/v1/dashboard")
  public Map<String, Object> dashboard(
      Authentication user,
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String dateFrom,
      @RequestParam(required = false) String dateTo,
      @RequestParam(defaultValue = "UTC") String timeZone) {
    return dashboard.read(user, projectId, dateFrom, dateTo, timeZone);
  }
}
