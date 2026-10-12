package com.example.cae.report;

import com.example.cae.artifact.ArtifactFileStore;
import com.example.cae.common.JsonCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

@Component
public class WorkerResponseVerifier {
  public record Verified(
      String type,
      String contentType,
      Map<String, Object> metadata,
      ArtifactFileStore.FileDescriptor file) {}

  private final ArtifactFileStore files;
  private final JsonCodec json;

  public WorkerResponseVerifier(ArtifactFileStore files, JsonCodec json) {
    this.files = files;
    this.json = json;
  }

  public List<Verified> verify(Map<String, Object> stage, Map<String, Object> response) {
    var request = json.readMap(stage.get("request"));
    String kind = stage.get("kind").toString();
    boolean post = kind.equals("POST");
    for (String key :
        List.of(
            "jobNo",
            "executionId",
            "executionRequestId",
            "parseRecordId",
            "parserVersion",
            "postVersion",
            "conditionCode",
            "subcase",
            "rawChecksum"))
      if (!equivalent(request.get(key), response.get(key)))
        throw new IllegalArgumentException("Renderer response source mismatch: " + key);
    if (!"COMPLETE".equals(response.get("status"))
        || !stage.get("requestFingerprint").equals(response.get("requestFingerprint")))
      throw new IllegalArgumentException("Render completion fingerprint mismatch");
    if (!post
        && (!"DRAFT".equals(response.get("reportStatus"))
            || !equivalent(request.get("reportVersion"), response.get("reportVersion"))))
      throw new IllegalArgumentException(
          "Renderer can publish only a draft of the selected version");
    String directory =
        "jobs/"
            + request.get("jobNo")
            + "/"
            + (post ? "derived" : "reports")
            + "/"
            + request.get("parserVersion")
            + "/"
            + (post ? "post" : "report")
            + "-v"
            + stage.get("stageVersion")
            + "/";
    Set<String> expected =
        post
            ? Set.of("contour.png", "mode-shape.png", "comparison.png", "curve.png")
            : Set.of("report.pptx");
    List<?> raw = (List<?>) response.get("artifacts");
    if (raw == null || raw.size() != expected.size())
      throw new IllegalArgumentException("Render artifact count mismatch");
    List<Verified> result = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (Object value : raw) {
      var row = json.readMap(value);
      String name = row.get("fileName").toString();
      if (!expected.contains(name) || !names.add(name))
        throw new IllegalArgumentException("Unexpected render artifact filename");
      var verified =
          descriptor(
              row,
              directory,
              name,
              request,
              post ? "IMAGE" : "REPORT_PPTX",
              post
                  ? "image/png"
                  : "application/vnd.openxmlformats-officedocument.presentationml.presentation");
      Path path = files.safe(verified.file().uri());
      if (post) png(path);
      else pptx(path);
      result.add(verified);
    }
    String name = post ? "post-manifest.json" : "report-manifest.json";
    var manifest =
        descriptor(
            json.readMap(response.get("manifest")),
            directory,
            name,
            request,
            post ? "POST_MANIFEST" : "REPORT_MANIFEST",
            "application/json");
    var document = files.readJson(files.safe(manifest.file().uri()));
    if (!stage.get("requestFingerprint").equals(document.value().get("requestFingerprint"))
        || !json.write(request).equals(json.write(json.readMap(document.value().get("request"))))) {
      // Object property order may differ between Java and Python.
      if (!request.equals(json.readMap(document.value().get("request")))
          || !stage.get("requestFingerprint").equals(document.value().get("requestFingerprint")))
        throw new IllegalArgumentException("Manifest request does not match the frozen stage");
    }
    List<?> listed = (List<?>) document.value().get("artifacts");
    if (listed == null || !json.write(raw).equals(json.write(listed))) {
      if (!raw.equals(listed))
        throw new IllegalArgumentException(
            "Manifest artifact descriptors differ from the response");
    }
    result.add(manifest);
    return result;
  }

  private Verified descriptor(
      Map<String, Object> row,
      String directory,
      String name,
      Map<String, Object> request,
      String type,
      String mime) {
    if (!name.equals(row.get("fileName"))
        || !(directory + name).equals(row.get("relativePath"))
        || !type.equals(row.get("artifactType"))
        || !mime.equals(row.get("contentType")))
      throw new IllegalArgumentException("Render artifact path/type mismatch");
    var metadata = json.readMap(row.get("metadata"));
    if (!equivalent(request.get("conditionCode"), metadata.get("conditionCode"))
        || !equivalent(request.get("subcase"), metadata.get("subcase")))
      throw new IllegalArgumentException("Report KPI and image condition/subcase differ");
    Path path =
        files.requireJob(row.get("relativePath").toString(), request.get("jobNo").toString());
    var actual = files.describe(path);
    if (actual.sizeBytes() > 32 * 1024 * 1024
        || !actual.checksum().equals(row.get("checksum"))
        || actual.sizeBytes() != ((Number) row.get("sizeBytes")).longValue())
      throw new IllegalArgumentException("Render file checksum/size mismatch");
    return new Verified(type, mime, metadata, actual);
  }

  private void png(Path path) {
    try (var input = Files.newInputStream(path)) {
      byte[] header = input.readNBytes(24);
      if (header.length != 24
          || !java.util.Arrays.equals(
              java.util.Arrays.copyOf(header, 8),
              new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
        throw new IllegalArgumentException("Invalid PNG signature");
      int width = ByteBuffer.wrap(header, 16, 4).getInt(),
          height = ByteBuffer.wrap(header, 20, 4).getInt();
      if (width < 400
          || height < 300
          || width > 4096
          || height > 4096
          || (long) width * height > 8_000_000)
        throw new IllegalArgumentException("Invalid PNG dimensions");
    } catch (IOException error) {
      throw new IllegalArgumentException("PNG unavailable", error);
    }
  }

  private void pptx(Path path) {
    try (ZipFile zip = new ZipFile(path.toFile())) {
      var entries = zip.stream().toList();
      long total = 0;
      int slides = 0, images = 0;
      if (entries.size() > 256 || zip.getEntry("[Content_Types].xml") == null)
        throw new IllegalArgumentException("Invalid PPTX package");
      for (var entry : entries) {
        if (entry.getSize() < 0 || entry.getSize() > 16 * 1024 * 1024)
          throw new IllegalArgumentException("PPTX entry too large");
        total += entry.getSize();
        if (entry.getName().matches("ppt/slides/slide[0-9]+\\.xml")) slides++;
        if (entry.getName().startsWith("ppt/media/")) images++;
      }
      if (total > 64 * 1024 * 1024 || slides < 4 || images < 4)
        throw new IllegalArgumentException("PPTX must contain four slides and four images");
    } catch (IOException error) {
      throw new IllegalArgumentException("Invalid PPTX ZIP", error);
    }
  }

  private boolean equivalent(Object left, Object right) {
    if (left instanceof Number && right instanceof Number)
      return new java.math.BigDecimal(left.toString())
              .compareTo(new java.math.BigDecimal(right.toString()))
          == 0;
    return java.util.Objects.equals(left, right);
  }
}
