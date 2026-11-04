package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed cache facade over a {@link ByteCache}: prefixes keys ({@code <service>:<cache>:<key>}),
 * serializes with a {@link ValueCodec} and loads missing values once per key at a time (single
 * flight) to prevent stampedes. Null results are not cached; empty and single-element collections
 * are cached like any other value.
 */
public final class AxizCache {

  private static final Logger LOG = LoggerFactory.getLogger(AxizCache.class);

  private final String prefix;
  private final ByteCache cache;
  private final ValueCodec codec;
  private final Duration defaultTtl;
  private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

  public AxizCache(
      String servicePrefix,
      String cacheName,
      ByteCache cache,
      ValueCodec codec,
      Duration defaultTtl) {
    if (!cacheName.matches("^[a-z0-9][a-z0-9.-]{0,62}$")) {
      throw new IllegalArgumentException("Invalid cache name " + cacheName);
    }
    this.prefix = servicePrefix + ":" + cacheName + ":";
    this.cache = Objects.requireNonNull(cache);
    this.codec = Objects.requireNonNull(codec);
    this.defaultTtl = Objects.requireNonNull(defaultTtl);
  }

  public String fullKey(String key) {
    return prefix + key;
  }

  public <T> Optional<T> get(String key, Class<T> type) {
    Optional<byte[]> bytes = cache.get(fullKey(key));
    if (bytes.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(codec.decode(bytes.get(), type));
    } catch (RuntimeException e) {
      LOG.warn("Discarding undecodable cache entry: {}", e.toString());
      cache.evict(fullKey(key));
      return Optional.empty();
    }
  }

  public void put(String key, Object value) {
    put(key, value, defaultTtl);
  }

  public void put(String key, Object value, Duration ttl) {
    cache.put(fullKey(key), codec.encode(value), ttl);
  }

  public void evict(String key) {
    cache.evict(fullKey(key));
  }

  /** Evicts every entry of this cache. */
  public long clear() {
    return cache.evictByPrefix(prefix);
  }

  /**
   * Returns the cached value or loads, caches and returns it.
   *
   * @param key key within this cache
   * @param type value type
   * @param loader loads the value from the source of truth (may return null: not cached)
   * @return the value or null
   */
  @SuppressWarnings("unchecked")
  public <T> @Nullable T getOrLoad(String key, Class<T> type, Supplier<@Nullable T> loader) {
    Optional<T> cached = get(key, type);
    if (cached.isPresent()) {
      return cached.get();
    }
    CompletableFuture<Object> mine = new CompletableFuture<>();
    CompletableFuture<Object> existing = inFlight.putIfAbsent(fullKey(key), mine);
    if (existing != null) {
      try {
        return (T) existing.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loader.get();
      } catch (ExecutionException e) {
        return loader.get();
      }
    }
    try {
      // A miss may have started before the preceding owner published its value and retired
      // its flight. Recheck after becoming the owner without extending an existing entry's TTL.
      Optional<T> published = get(key, type);
      if (published.isPresent()) {
        T value = published.get();
        mine.complete(value);
        return value;
      }
      T value = loader.get();
      if (value != null) {
        put(key, value);
      }
      mine.complete(value);
      return value;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inFlight.remove(fullKey(key), mine);
    }
  }
}
