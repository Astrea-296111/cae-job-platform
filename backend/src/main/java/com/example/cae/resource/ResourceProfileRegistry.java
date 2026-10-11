package com.example.cae.resource;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.domain.AnalysisType;
import com.example.cae.job.repository.JobRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Editable admission defaults; each Job retains the version and values selected at creation. */
@Service
public class ResourceProfileRegistry {
  public record Definition(
      @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}") @NotNull String name,
      @Min(1) @Max(32) int cpu,
      @Min(1) @Max(64) int memoryGb,
      @Min(1) @Max(120) int walltimeMin,
      @Min(1) @Max(16) int licenseTokens,
      @NotEmpty List<@NotNull AnalysisType> allowedAnalysisTypes,
      boolean enabled,
      @Min(0) long version) {}

  public record Snapshot(
      String name, long version, int licenseTokens, Map<String, Object> resource) {}

  private final JdbcTemplate jdbc;
  private final JsonCodec json;
  private final ProjectAccess access;
  private final JobRepository jobs;

  public ResourceProfileRegistry(
      JdbcTemplate jdbc, JsonCodec json, ProjectAccess access, JobRepository jobs) {
    this.jdbc = jdbc;
    this.json = json;
    this.access = access;
    this.jobs = jobs;
  }

  public List<Map<String, Object>> list() {
    var rows = jdbc.query("SELECT * FROM cae_resource_profile ORDER BY name", SqlRows.MAPPER);
    rows.forEach(this::decode);
    return rows;
  }

  public Map<String, Object> find(String name) {
    var rows = jdbc.query("SELECT * FROM cae_resource_profile WHERE name=?", SqlRows.MAPPER, name);
    if (rows.isEmpty()) throw ApiException.notFound("Resource profile");
    return decode(rows.get(0));
  }

  public Snapshot select(String name, AnalysisType analysis, Map<String, Object> requested) {
    Map<String, Object> profile = find(name);
    if (!Boolean.TRUE.equals(profile.get("enabled"))
        && !(profile.get("enabled") instanceof Number n && n.intValue() != 0))
      throw ApiException.invalid("Resource profile is disabled");
    if (!((List<?>) profile.get("allowedAnalysisTypes")).contains(analysis.name()))
      throw ApiException.invalid("Resource profile does not support the requested analysis type");
    Map<String, Object> values =
        new java.util.LinkedHashMap<>(
            Map.of(
                "cpu",
                profile.get("cpu"),
                "memoryGb",
                profile.get("memoryGb"),
                "walltimeMin",
                profile.get("walltimeMin")));
    if (requested != null) {
      for (String key : requested.keySet())
        if (!values.containsKey(key))
          throw ApiException.invalid("Unsupported resource field: " + key);
      values.putAll(requested);
    }
    range(values, "cpu", 32);
    range(values, "memoryGb", 64);
    range(values, "walltimeMin", 120);
    return new Snapshot(
        name,
        ((Number) profile.get("version")).longValue(),
        ((Number) profile.get("licenseTokens")).intValue(),
        values);
  }

  @Transactional
  public Map<String, Object> create(Authentication user, Definition value) {
    requireAdmin(user);
    jdbc.update(
        "INSERT INTO cae_resource_profile(name,cpu,memory_gb,walltime_min,license_tokens,allowed_analysis_types,enabled) VALUES(?,?,?,?,?,?,?)",
        value.name(),
        value.cpu(),
        value.memoryGb(),
        value.walltimeMin(),
        value.licenseTokens(),
        json.write(value.allowedAnalysisTypes()),
        value.enabled());
    Map<String, Object> result = find(value.name());
    audit(user, "RESOURCE_PROFILE_CREATE", value.name(), null, result);
    return result;
  }

  @Transactional
  public Map<String, Object> update(Authentication user, String name, Definition value) {
    requireAdmin(user);
    if (!name.equals(value.name()))
      throw ApiException.invalid("Resource profile name is immutable");
    Map<String, Object> before = find(name);
    int count =
        jdbc.update(
            "UPDATE cae_resource_profile SET cpu=?,memory_gb=?,walltime_min=?,license_tokens=?,allowed_analysis_types=?,enabled=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE name=? AND version=?",
            value.cpu(),
            value.memoryGb(),
            value.walltimeMin(),
            value.licenseTokens(),
            json.write(value.allowedAnalysisTypes()),
            value.enabled(),
            name,
            value.version());
    if (count != 1)
      throw ApiException.conflict("Resource profile changed concurrently; refresh its version");
    Map<String, Object> result = find(name);
    audit(user, "RESOURCE_PROFILE_UPDATE", name, before, result);
    return result;
  }

  @Transactional
  public void delete(Authentication user, String name, long version) {
    requireAdmin(user);
    Map<String, Object> before = find(name);
    if (jdbc.update("DELETE FROM cae_resource_profile WHERE name=? AND version=?", name, version)
        != 1)
      throw ApiException.conflict("Resource profile changed concurrently; refresh its version");
    audit(user, "RESOURCE_PROFILE_DELETE", name, before, null);
  }

  private Map<String, Object> decode(Map<String, Object> row) {
    row.put(
        "allowedAnalysisTypes",
        json.readMap("{\"items\":" + row.get("allowedAnalysisTypes") + "}").get("items"));
    return row;
  }

  private void requireAdmin(Authentication user) {
    if (!access.admin(user)) throw ApiException.forbidden();
  }

  private void audit(
      Authentication user, String operation, String name, Object before, Object after) {
    jobs.audit(user.getName(), operation, "RESOURCE_PROFILE", name, before, after);
  }

  private void range(Map<String, Object> values, String key, int max) {
    Object value = values.get(key);
    if (!(value instanceof Number n)
        || n.doubleValue() != n.intValue()
        || n.intValue() < 1
        || n.intValue() > max)
      throw ApiException.invalid("Resource " + key + " must be an integer between 1 and " + max);
  }
}
