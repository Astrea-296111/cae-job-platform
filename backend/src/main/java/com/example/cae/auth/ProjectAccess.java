package com.example.cae.auth;

import com.example.cae.common.ApiException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class ProjectAccess {
  private final JdbcTemplate jdbc;

  public ProjectAccess(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean admin(Authentication user) {
    return hasRole(user, "ADMIN");
  }

  public boolean lead(Authentication user) {
    return hasRole(user, "PROJECT_LEAD");
  }

  public boolean hasRole(Authentication user, String role) {
    return user != null
        && user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
  }

  public void require(Authentication user, long projectId) {
    Integer exists =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cae_project WHERE id=?", Integer.class, projectId);
    if (exists == null || exists == 0) throw ApiException.notFound("Project");
    if (admin(user)) return;
    Integer allowed =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cae_project_member WHERE project_id=? AND username=?",
            Integer.class,
            projectId,
            user.getName());
    if (allowed == null || allowed == 0) throw ApiException.forbidden();
  }

  public void requireJob(Authentication user, Map<String, Object> job) {
    require(user, ((Number) job.get("projectId")).longValue());
  }

  public void requireOwner(Authentication user, Map<String, Object> job) {
    requireJob(user, job);
    if (!admin(user) && !job.get("submitterId").equals(user.getName()))
      throw ApiException.forbidden();
  }

  public void requireLead(Authentication user, long projectId) {
    require(user, projectId);
    if (!admin(user) && !lead(user)) throw ApiException.forbidden();
  }
}
