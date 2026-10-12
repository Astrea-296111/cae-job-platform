package com.example.cae.report;

import com.example.cae.artifact.ArtifactFileStore;
import com.example.cae.common.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class RenderFingerprint {
  private final JsonCodec json;

  public RenderFingerprint(JsonCodec json) {
    this.json = json;
  }

  public String of(Map<String, Object> payload) {
    return ArtifactFileStore.hex(json.write(sorted(payload)).getBytes(StandardCharsets.UTF_8));
  }

  private Object sorted(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, item) -> sorted.put(key.toString(), sorted(item)));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
    return value;
  }
}
