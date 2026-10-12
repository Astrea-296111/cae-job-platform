package com.example.cae.dashboard;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.resource.NodeRegistryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final DashboardRepository repository;
  private final ProjectAccess access;
  private final NodeRegistryService nodes;

  public DashboardService(
      DashboardRepository repository, ProjectAccess access, NodeRegistryService nodes) {
    this.repository = repository;
    this.access = access;
    this.nodes = nodes;
  }

  public Map<String, Object> read(
      Authentication user, Long project, String from, String to, String timeZone) {
    if (project != null) access.require(user, project);
    Object begin = null, end = null;
    try {
      var zone = ZoneId.of(timeZone);
      if (from != null)
        begin = Timestamp.from(LocalDate.parse(from).atStartOfDay(zone).toInstant());
      if (to != null)
        end = Timestamp.from(LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant());
    } catch (DateTimeException invalid) {
      throw ApiException.invalid("Invalid date or time zone");
    }
    if (begin != null && end != null && !((Timestamp) begin).before((Timestamp) end))
      throw ApiException.invalid("Date window must be ordered");
    Object[] values = {
      project, project, begin, begin, end, end, access.admin(user), user.getName()
    };
    var counts = repository.counts(values);
    long success = ((Number) counts.get("success")).longValue(),
        failed = ((Number) counts.get("failed")).longValue();
    var rate = new LinkedHashMap<String, Object>();
    rate.put("numerator", success);
    rate.put("denominator", success + failed);
    rate.put(
        "percent",
        success + failed == 0
            ? null
            : BigDecimal.valueOf(success * 100)
                .divide(BigDecimal.valueOf(success + failed), 2, RoundingMode.HALF_UP));
    var result = new LinkedHashMap<String, Object>();
    result.put("asOf", Instant.now().toString());
    result.put("timeZone", timeZone);
    result.put("counts", counts);
    result.put("successRate", rate);
    result.put("averageQueueSeconds", repository.queueTime(values));
    result.put("byAnalysisType", repository.distribution(values));
    result.put("recentJobs", repository.recent(values));
    result.put("nodes", nodes.nodes());
    result.put(
        "scope", "Jobs created in the selected window; node resources are current observations");
    return result;
  }
}
