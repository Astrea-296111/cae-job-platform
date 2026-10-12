package com.example.cae.job.application;

import com.example.cae.auth.ProjectAccess;
import com.example.cae.common.ApiException;
import com.example.cae.common.JsonCodec;
import com.example.cae.common.SqlRows;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class JobQueryCache {
  private final JdbcTemplate jdbc;
  private final ProjectAccess access;
  private final StringRedisTemplate redis;
  private final JsonCodec json;
  private final String prefix;

  public JobQueryCache(
      JdbcTemplate jdbc,
      ProjectAccess access,
      StringRedisTemplate redis,
      JsonCodec json,
      @Value("${CAE_CACHE_NAMESPACE:cae}") String prefix) {
    this.jdbc = jdbc;
    this.access = access;
    this.redis = redis;
    this.json = json;
    this.prefix = prefix + ":job:";
  }

  public Map<String, Object> get(
      Authentication user, String jobNo, Supplier<Map<String, Object>> loader) {
    var identity =
        jdbc
            .query(
                "SELECT id,project_id,version FROM cae_job WHERE job_no=?", SqlRows.MAPPER, jobNo)
            .stream()
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("Job"));
    access.require(user, ((Number) identity.get("projectId")).longValue());
    String key = prefix + jobNo;
    try {
      String cached = redis.opsForValue().get(key);
      if (cached != null) {
        var value = json.readMap(cached);
        if (((Number) value.get("version")).longValue()
            == ((Number) identity.get("version")).longValue()) return value;
      }
    } catch (RuntimeException unavailable) {
      /* MySQL remains authoritative. */
    }
    var value = loader.get();
    try {
      var script =
          new DefaultRedisScript<Long>(
              "local v=tonumber(redis.call('GET',KEYS[2]) or '-1');local n=tonumber(ARGV[1]);if n>=v then redis.call('SET',KEYS[2],ARGV[1],'EX',60);redis.call('SET',KEYS[1],ARGV[2],'EX',5);return 1 end;return 0",
              Long.class);
      redis.execute(
          script,
          List.of(key, key + ":version"),
          value.get("version").toString(),
          json.write(value));
    } catch (RuntimeException unavailable) {
      /* Cache failure never blocks a query. */
    }
    return value;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void invalidate(JobChanged event) {
    String key = prefix + event.jobNo();
    try {
      var script =
          new DefaultRedisScript<Long>(
              "local v=tonumber(redis.call('GET',KEYS[2]) or '-1');local n=tonumber(ARGV[1]);if n>=v then redis.call('SET',KEYS[2],ARGV[1],'EX',60);redis.call('DEL',KEYS[1]);return 1 end;return 0",
              Long.class);
      redis.execute(script, List.of(key, key + ":version"), Long.toString(event.version()));
    } catch (RuntimeException unavailable) {
      /* The short TTL still bounds projection lifetime. */
    }
  }
}
