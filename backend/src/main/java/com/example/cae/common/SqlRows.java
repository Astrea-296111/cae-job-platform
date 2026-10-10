package com.example.cae.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;

public final class SqlRows {
  private SqlRows() {}

  public static final RowMapper<Map<String, Object>> MAPPER = SqlRows::map;

  public static Map<String, Object> map(ResultSet rs, int row) throws SQLException {
    Map<String, Object> result = new LinkedHashMap<>();
    var meta = rs.getMetaData();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      Object value = rs.getObject(i);
      if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
      result.put(lowerCamel(meta.getColumnLabel(i)), value);
    }
    return result;
  }

  public static String lowerCamel(String value) {
    String[] parts = value.toLowerCase(Locale.ROOT).split("_");
    StringBuilder output = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      if (!parts[i].isEmpty())
        output.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
    }
    return output.toString();
  }

  public static long id(Map<String, Object> value) {
    return ((Number) value.get("id")).longValue();
  }
}
