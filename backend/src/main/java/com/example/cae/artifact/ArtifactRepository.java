package com.example.cae.artifact;

import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ArtifactRepository {
  private final JdbcTemplate jdbc;
  private final JsonCodec json;
  private final ArtifactFileStore files;

  public ArtifactRepository(JdbcTemplate jdbc, JsonCodec json, ArtifactFileStore files) {
    this.jdbc = jdbc;
    this.json = json;
    this.files = files;
  }

  public Map<String, Object> find(long id) {
    return jdbc.query("SELECT * FROM cae_artifact WHERE id=?", SqlRows.MAPPER, id).stream()
        .findFirst()
        .map(this::decode)
        .orElseThrow(() -> ApiException.notFound("Artifact"));
  }

  public List<Map<String, Object>> list(long jobId) {
    return jdbc
        .query("SELECT * FROM cae_artifact WHERE job_id=? ORDER BY id", SqlRows.MAPPER, jobId)
        .stream()
        .map(this::decode)
        .toList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> register(
      long jobId,
      long executionId,
      Long parseRecordId,
      String parserVersion,
      int stageVersion,
      String artifactType,
      ArtifactFileStore.FileDescriptor file,
      String contentType,
      Map<String, Object> metadata) {
    List<Map<String, Object>> identities =
        jdbc.query(
            "SELECT j.job_no,e.job_id FROM cae_job_execution e JOIN cae_job j ON j.id=e.job_id WHERE e.id=? AND e.job_id=?",
            SqlRows.MAPPER,
            executionId,
            jobId);
    if (identities.isEmpty())
      throw new IllegalArgumentException("Artifact execution does not belong to the Job");
    files.requireJob(file.uri(), identities.get(0).get("jobNo").toString());
    if (parseRecordId != null
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM cae_result_parse_record WHERE id=? AND job_id=? AND execution_id=? AND parser_version=?",
                Integer.class,
                parseRecordId,
                jobId,
                executionId,
                parserVersion)
            != 1)
      throw new IllegalArgumentException(
          "Artifact parse identity does not belong to this Job and execution");
    if (stageVersion < 0
        || artifactType == null
        || !artifactType.matches("[A-Z_]{1,32}")
        || !file.checksum().matches("[a-f0-9]{64}")
        || file.sizeBytes() < 0) throw new IllegalArgumentException("Artifact metadata is invalid");
    jdbc.update(
        "INSERT INTO cae_artifact(job_id,execution_id,parse_record_id,parser_version,stage_version,artifact_type,uri,file_name,size_bytes,checksum,content_type,metadata) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
        jobId,
        executionId,
        parseRecordId,
        parserVersion,
        stageVersion,
        artifactType,
        file.uri(),
        file.fileName(),
        file.sizeBytes(),
        file.checksum(),
        contentType,
        json.write(metadata));
    Map<String, Object> row =
        jdbc.query(
                "SELECT * FROM cae_artifact WHERE job_id=? AND uri=?",
                SqlRows.MAPPER,
                jobId,
                file.uri())
            .get(0);
    if (((Number) row.get("executionId")).longValue() != executionId
        || !row.get("checksum").equals(file.checksum())
        || ((Number) row.get("sizeBytes")).longValue() != file.sizeBytes()
        || !row.get("artifactType").equals(artifactType)
        || !row.get("parserVersion").equals(parserVersion)
        || ((Number) row.get("stageVersion")).intValue() != stageVersion
        || !row.get("contentType").equals(contentType)
        || !json.readMap(row.get("metadata")).equals(json.readMap(json.write(metadata))))
      throw new IllegalArgumentException("Registered artifact identity cannot be overwritten");
    return decode(row);
  }

  private Map<String, Object> decode(Map<String, Object> row) {
    row.put("metadata", json.readMap(row.get("metadata")));
    row.put("downloadUrl", "/api/v1/artifacts/" + row.get("id") + "/download");
    return row;
  }
}
