package com.example.cae.project;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.SqlRows;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
  private final JdbcTemplate jdbc;
  private final ProjectAccess access;

  public ProjectService(JdbcTemplate jdbc, ProjectAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public List<Map<String, Object>> projects(Authentication user) {
    if (access.admin(user))
      return jdbc.query("SELECT * FROM cae_project ORDER BY id", SqlRows.MAPPER);
    return jdbc.query(
        "SELECT p.* FROM cae_project p JOIN cae_project_member m ON m.project_id=p.id WHERE m.username=? ORDER BY p.id",
        SqlRows.MAPPER,
        user.getName());
  }

  @Transactional
  public Map<String, Object> createProject(
      Authentication user, String code, String name, String description) {
    long id =
        insert(
            "INSERT INTO cae_project(code,name,description,owner_id) VALUES(?,?,?,?)",
            code,
            name,
            description,
            user.getName());
    jdbc.update(
        "INSERT INTO cae_project_member(project_id,username,member_role) VALUES(?,?,?)",
        id,
        user.getName(),
        access.lead(user) ? "PROJECT_LEAD" : "CAE_ENGINEER");
    return jdbc.queryForObject("SELECT * FROM cae_project WHERE id=?", SqlRows.MAPPER, id);
  }

  public List<Map<String, Object>> models(Authentication user, Long projectId) {
    if (projectId != null) {
      access.require(user, projectId);
      return jdbc.query(
          "SELECT * FROM cae_model WHERE project_id=? ORDER BY id", SqlRows.MAPPER, projectId);
    }
    if (access.admin(user))
      return jdbc.query("SELECT * FROM cae_model ORDER BY id", SqlRows.MAPPER);
    return jdbc.query(
        "SELECT m.* FROM cae_model m JOIN cae_project_member p ON p.project_id=m.project_id WHERE p.username=? ORDER BY m.id",
        SqlRows.MAPPER,
        user.getName());
  }

  @Transactional
  public Map<String, Object> createModel(
      Authentication user, long projectId, String code, String name) {
    access.require(user, projectId);
    long id =
        insert("INSERT INTO cae_model(project_id,code,name) VALUES(?,?,?)", projectId, code, name);
    return jdbc.queryForObject("SELECT * FROM cae_model WHERE id=?", SqlRows.MAPPER, id);
  }

  public List<Map<String, Object>> revisions(Authentication user, Long projectId) {
    if (projectId != null) {
      access.require(user, projectId);
      return jdbc.query(
          "SELECT r.*,m.project_id,m.name AS model_name,m.code AS model_code FROM cae_model_revision r JOIN cae_model m ON m.id=r.model_id WHERE m.project_id=? ORDER BY r.id",
          SqlRows.MAPPER,
          projectId);
    }
    if (access.admin(user))
      return jdbc.query(
          "SELECT r.*,m.project_id,m.name AS model_name,m.code AS model_code FROM cae_model_revision r JOIN cae_model m ON m.id=r.model_id ORDER BY r.id",
          SqlRows.MAPPER);
    return jdbc.query(
        "SELECT r.*,m.project_id,m.name AS model_name,m.code AS model_code FROM cae_model_revision r JOIN cae_model m ON m.id=r.model_id JOIN cae_project_member p ON p.project_id=m.project_id WHERE p.username=? ORDER BY r.id",
        SqlRows.MAPPER,
        user.getName());
  }

  @Transactional
  public Map<String, Object> createRevision(
      Authentication user, long modelId, String revision, String description) {
    List<Long> projects =
        jdbc.queryForList("SELECT project_id FROM cae_model WHERE id=?", Long.class, modelId);
    if (projects.isEmpty()) throw ApiException.notFound("Model");
    access.require(user, projects.get(0));
    long id =
        insert(
            "INSERT INTO cae_model_revision(model_id,revision,description) VALUES(?,?,?)",
            modelId,
            revision,
            description);
    return jdbc.queryForObject("SELECT * FROM cae_model_revision WHERE id=?", SqlRows.MAPPER, id);
  }

  public void requireRevision(long projectId, long modelRevisionId) {
    Integer matching =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cae_model_revision r JOIN cae_model m ON m.id=r.model_id WHERE r.id=? AND m.project_id=?",
            Integer.class,
            modelRevisionId,
            projectId);
    if (matching == null || matching == 0)
      throw ApiException.invalid("Model revision must belong to the selected project");
  }

  private long insert(String sql, Object... args) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
          return statement;
        },
        keys);
    return keys.getKey().longValue();
  }
}
