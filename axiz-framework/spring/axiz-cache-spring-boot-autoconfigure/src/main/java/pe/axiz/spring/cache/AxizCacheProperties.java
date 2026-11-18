package pe.axiz.spring.cache;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Two-level cache ({@code axiz.cache.*}): L1 in memory per instance, L2 through an optional shared
 * cache port. Remote cache outages degrade to L1 without failing requests.
 *
 * @param prefix key prefix; defaults to the application name
 * @param defaultTtl TTL of caches without their own entry
 * @param l1 local level
 * @param l2 shared cache level policy
 * @param caches TTL per cache name
 */
@ConfigurationProperties("axiz.cache")
public record AxizCacheProperties(
    @Nullable String prefix,
    @DefaultValue("10m") Duration defaultTtl,
    @DefaultValue L1 l1,
    @DefaultValue L2 l2,
    @DefaultValue Map<String, CacheSpec> caches) {
  public AxizCacheProperties {
    caches = Collections.unmodifiableMap(new LinkedHashMap<>(caches));
  }

  /** Shared cache policy; any integration can supply the L2 port. */
  public record L2(@DefaultValue("true") boolean enabled) {}

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
