package pe.axiz.spring.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import pe.axiz.core.cache.AxizCache;
import pe.axiz.core.cache.ByteCache;
import pe.axiz.core.cache.ValueCodec;

/** Named caches over the shared two-level store, with the TTL configured per name. */
public final class AxizCaches {

  private final String prefix;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ByteCache is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final ByteCache store;

  private final ValueCodec codec;
  private final AxizCacheProperties properties;
  private final Map<String, AxizCache> caches = new ConcurrentHashMap<>();

  public AxizCaches(
      String prefix, ByteCache store, ValueCodec codec, AxizCacheProperties properties) {
    this.prefix = Objects.requireNonNull(prefix);
    this.store = Objects.requireNonNull(store);
    this.codec = Objects.requireNonNull(codec);
    this.properties = Objects.requireNonNull(properties);
  }

  public AxizCache cache(String name) {
    return caches.computeIfAbsent(name, n -> new AxizCache(prefix, n, store, codec, ttl(n)));
  }

  public Duration ttl(String name) {
    AxizCacheProperties.CacheSpec spec = properties.caches().get(name);
    return spec != null ? spec.ttl() : properties.defaultTtl();
  }

  ByteCache store() {
    return store;
  }

  String prefix() {
    return prefix;
  }
}
