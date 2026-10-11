package com.example.cae.artifact;

import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CaseCatalogService {
  private final Path caseRoot;
  private final JsonCodec json;

  public CaseCatalogService(@Value("${CASE_ROOT:../demo-data}") String caseRoot, JsonCodec json) {
    this.caseRoot = Path.of(caseRoot).toAbsolutePath().normalize();
    this.json = json;
  }

  public List<Map<String, Object>> list() {
    List<Map<String, Object>> cases = new ArrayList<>();
    try (var paths = Files.walk(caseRoot, 4)) {
      Path realRoot = caseRoot.toRealPath();
      for (Path manifest :
          paths.filter(p -> p.getFileName().toString().equals("manifest.json")).sorted().toList()) {
        if (Files.isSymbolicLink(manifest) || !manifest.toRealPath().startsWith(realRoot)) continue;
        Map<String, Object> source = json.readMap(Files.readString(manifest));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(
            "casePath", caseRoot.relativize(manifest.getParent()).toString().replace('\\', '/'));
        for (String key : List.of("caseId", "analysisType", "conditionCode", "modelRevision"))
          entry.put(key, source.get(key));
        Object subcase =
            source.get("parameters") instanceof Map<?, ?> parameters
                ? parameters.get("subcase")
                : null;
        entry.put("subcase", subcase == null ? 1 : subcase);
        entry.put("synthetic", true);
        cases.add(entry);
      }
      return cases;
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CASE_ROOT_UNAVAILABLE",
          "Configured synthetic case root is unavailable",
          Map.of());
    }
  }
}
