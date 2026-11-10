package pe.axiz.spring.cache;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Two-level cache ({@code axiz.cache.*}): L1 in memory per instance, L2 on ElastiCache (Redis or
 * Valkey) shared by the replicas. Redis outages degrade to L1 without failing requests.
 *
 * @param prefix key prefix; defaults to the application name
 * @param defaultTtl TTL of caches without their own entry
 * @param l1 local level
 * @param redis whether the Redis level is used when a connection is configured
 * @param caches TTL per cache name
 */
@ConfigurationProperties("axiz.cache")
public record AxizCacheProperties(
    @Nullable String prefix,
    @DefaultValue("10m") Duration defaultTtl,
    @DefaultValue L1 l1,
    @DefaultValue("true") boolean redis,
    @DefaultValue Map<String, CacheSpec> caches) {
  public AxizCacheProperties {
    caches = Collections.unmodifiableMap(new LinkedHashMap<>(caches));
  }

  /**
   * Local level.
   *
   * @param maxEntries entries kept per instance
   * @param maxTtl local entries never live longer than this, so replicas converge quickly
   */
  public record L1(@DefaultValue("10000") int maxEntries, @DefaultValue("1m") Duration maxTtl) {}

  /**
   * One cache.
   *
   * @param ttl time to live
   */
  public record CacheSpec(@DefaultValue("10m") Duration ttl) {}
}
