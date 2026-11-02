package pe.axiz.spring.cache;

import java.time.Duration;
import java.util.Objects;
import pe.axiz.core.cache.AsyncByteCache;

/** Shared cache identity, TTLs and native asynchronous byte store. */
public class AsyncCacheSettings {
  private final String prefix;
  private final AsyncByteCache store;
  private final AxizCacheProperties properties;

  public AsyncCacheSettings(String prefix, AsyncByteCache store, AxizCacheProperties properties) {
    this.prefix = Objects.requireNonNull(prefix);
    this.store = Objects.requireNonNull(store);
    this.properties = Objects.requireNonNull(properties);
  }

  public String prefix() {
    return prefix;
  }

  public AsyncByteCache store() {
    return store;
  }

  public Duration ttl(String name) {
    var spec = properties.caches().get(name);
    return spec == null ? properties.defaultTtl() : spec.ttl();
  }
}
