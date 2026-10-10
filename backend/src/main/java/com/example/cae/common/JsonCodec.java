package com.example.cae.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonCodec {
  private final ObjectMapper mapper;

  public JsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Value cannot be encoded as JSON", e);
    }
  }

  public Map<String, Object> readMap(Object value) {
    if (value == null) return Map.of();
    if (value instanceof Map<?, ?> map)
      return mapper.convertValue(map, new TypeReference<Map<String, Object>>() {});
    try {
      return mapper.readValue(value.toString(), new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Stored JSON is invalid", e);
    }
  }
}
