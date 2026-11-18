package pe.axiz.spring.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Objects;
import pe.axiz.core.cache.AsyncByteCache;

/** Shared cache identity, TTLs and native asynchronous byte store. */
public class AsyncCacheSettings {
  private final String prefix;

  private final AsyncByteCache store;

  private final AxizCacheProperties properties;

  @SuppressFBWarnings(
      value = {"CT_CONSTRUCTOR_THROW", "EI_EXPOSE_REP2"},
      justification =
          "ReactiveCaches extends these shared settings; the constructor validates borrowed "
              + "collaborators and acquires no external resources.")
  public AsyncCacheSettings(String prefix, AsyncByteCache store, AxizCacheProperties properties) {
    this.prefix = Objects.requireNonNull(prefix);
    this.store = Objects.requireNonNull(store);
    this.properties = Objects.requireNonNull(properties);
  }

  public String prefix() {
    return prefix;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The accessor returns the configured borrowed cache collaborator; it does not transfer"
              + " ownership.")
  public AsyncByteCache store() {
    return store;
  }

  public Duration ttl(String name) {
    var spec = properties.caches().get(name);
    return spec == null ? properties.defaultTtl() : spec.ttl();
  }
}
