package com.example.cae.report;

import com.example.cae.artifact.ArtifactFileStore;
import com.example.cae.artifact.ArtifactRepository;
import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import com.example.cae.job.validation.InputSnapshotService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PipelineRequestBuilder {
  private final PipelineRepository repository;
  private final ArtifactRepository artifacts;
  private final ArtifactFileStore files;
  private final InputSnapshotService snapshots;
  private final BaselineComparisonService comparison;
  private final JsonCodec json;

  public PipelineRequestBuilder(
      PipelineRepository repository,
      ArtifactRepository artifacts,
      ArtifactFileStore files,
      InputSnapshotService snapshots,
      BaselineComparisonService comparison,
      JsonCodec json) {
    this.repository = repository;
    this.artifacts = artifacts;
    this.files = files;
    this.snapshots = snapshots;
    this.comparison = comparison;
    this.json = json;
  }

  public Map<String, Object> post(
      Map<String, Object> job,
      Map<String, Object> execution,
      Map<String, Object> parse,
      int version,
      String baselineJob) {
    snapshots.verifySnapshot(job);
    Map<String, Object> result =
        artifacts.list(SqlRows.id(job)).stream()
            .filter(
                row ->
                    row.get("artifactType").equals("RESULT_JSON")
                        && java.util.Objects.equals(row.get("parseRecordId"), parse.get("id")))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("Selected canonical result artifact is missing"));
    var descriptor =
        files.describe(files.requireJob(result.get("uri").toString(), job.get("jobNo").toString()));
    if (!descriptor.checksum().equals(result.get("checksum")))
      throw new IllegalArgumentException("Canonical result artifact changed");
    List<Map<String, Object>> metrics = repository.metrics(SqlRows.id(parse));
    if (metrics.size() != ((Number) parse.get("metricCount")).intValue())
      throw new IllegalArgumentException("Canonical metric set is incomplete");
    for (var row : metrics)
      if (!row.get("conditionCode").equals(parse.get("conditionCode"))
          || !row.get("subcase").equals(parse.get("subcase")))
        throw new IllegalArgumentException(
            "Canonical metric condition differs from selected parse");
    var base = repository.baseline(job, parse, baselineJob);
    if (baselineJob != null && base.isEmpty())
      throw ApiException.invalid("Baseline must be a compatible successful Job and parser version");
    List<Map<String, Object>> baseline =
        base.isEmpty() ? List.of() : repository.metrics(SqlRows.id(base.get()));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("jobNo", job.get("jobNo"));
    body.put("executionId", SqlRows.id(execution));
    body.put("executionRequestId", execution.get("submitRequestId"));
    body.put("externalExecutionId", execution.get("externalExecutionId"));
    body.put("nodeId", execution.get("nodeId"));
    body.put("pid", execution.get("pid"));
    body.put("processIdentity", execution.get("processIdentity"));
    body.put("parseRecordId", SqlRows.id(parse));
    body.put("parserVersion", parse.get("parserVersion"));
    body.put("postVersion", version);
    body.put("analysisType", parse.get("analysisType"));
    body.put("conditionCode", parse.get("conditionCode"));
    body.put("subcase", parse.get("subcase"));
    body.put("rawChecksum", parse.get("rawChecksum"));
    body.put("snapshotChecksum", job.get("inputChecksum"));
    body.put("resultJsonChecksum", descriptor.checksum());
    var context = new LinkedHashMap<>(repository.context(SqlRows.id(job)));
    context.put("maturity", parse.get("maturity"));
    body.put("context", context);
    List<Map<String, Object>> canonical = new ArrayList<>();
    for (var metric : metrics) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (String key :
          List.of(
              "metricCode",
              "metricName",
              "valueText",
              "unit",
              "dimension",
              "status",
              "conditionCode",
              "subcase")) row.put(key, metric.get(key));
      row.put(
          "valueNum",
          metric.get("valueNum") == null
              ? null
              : BaselineComparisonService.plain(new BigDecimal(metric.get("valueNum").toString())));
      canonical.add(row);
    }
    body.put("metrics", canonical);
    body.put("comparisons", comparison.compare(metrics, baseline));
    body.put(
        "baseline",
        base.isEmpty()
            ? null
            : Map.of("jobNo", base.get().get("jobNo"), "parseRecordId", SqlRows.id(base.get())));
    List<Map<String, Object>> specs = new ArrayList<>();
    String[] kinds = {"CONTOUR", "MODE_SHAPE", "COMPARISON", "CURVE"};
    String[] names = {"contour.png", "mode-shape.png", "comparison.png", "curve.png"};
    for (int i = 0; i < 4; i++)
      specs.add(
          Map.of(
              "conditionCode",
              parse.get("conditionCode"),
              "resultType",
              kinds[i],
              "component",
              "SYNTHETIC_BODY",
              "subcase",
              parse.get("subcase"),
              "frame",
              0,
              "view",
              "ISOMETRIC",
              "selectionSet",
              "PUBLIC_DEMO",
              "outputName",
              names[i]));
    body.put("specs", specs);
    return body;
  }

  public Map<String, Object> report(Map<String, Object> post, int version) {
    if (!post.get("status").equals("COMPLETE")) throw new PipelineService.StageBusyException();
    Map<String, Object> request = new LinkedHashMap<>(json.readMap(post.get("request")));
    request.remove("specs");
    var manifest = artifacts.find(((Number) post.get("manifestArtifactId")).longValue());
    var descriptor = files.describe(files.safe(manifest.get("uri").toString()));
    if (!descriptor.checksum().equals(manifest.get("checksum")))
      throw new IllegalArgumentException("Selected post manifest changed");
    request.put("reportVersion", version);
    request.put(
        "templateCode",
        request.get("analysisType").equals("STRUCTURE") ? "STRUCTURE_DRAFT" : "PILOT_DRAFT");
    request.put("templateVersion", "v1");
    request.put("generatedAt", Instant.now().toString());
    request.put("postManifestChecksum", descriptor.checksum());
    request.put("postRequestFingerprint", post.get("requestFingerprint"));
    return request;
  }
}
