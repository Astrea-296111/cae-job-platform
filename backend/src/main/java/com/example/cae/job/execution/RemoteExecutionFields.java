package com.example.cae.job.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Whitelisted remote facts; never accepts an Agent-supplied filesystem path. */
public final class RemoteExecutionFields {
  private RemoteExecutionFields() {}

  public static Map<String, Object> from(Map<String, Object> remote) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (String key :
        new String[] {
          "pid", "exitCode", "progress", "processIdentity", "rawSolverCode", "rawMessage"
        }) {
      if (remote.containsKey(key)) {
        Object value = remote.get(key);
        if (value instanceof String text)
          value = text.substring(0, Math.min(text.length(), key.equals("rawMessage") ? 2000 : 128));
        fields.put(key, value);
      }
    }
    for (String key : new String[] {"startedAt", "finishedAt"})
      if (remote.get(key) != null)
        fields.put(key, Timestamp.from(Instant.parse(remote.get(key).toString())));
    return fields;
  }
}
