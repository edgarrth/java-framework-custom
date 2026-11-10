package pe.axiz.spring.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.util.Optional;
import pe.axiz.core.cache.LocalByteCache;

/** L1 on Caffeine with a TTL per entry and size-based eviction. */
public final class CaffeineByteCache implements LocalByteCache {

  /** Internal holder; a class rather than a record because it holds an array. */
  private static final class Entry {
    private final byte[] value;
    private final long ttlNanos;

    Entry(byte[] value, long ttlNanos) {
      this.value = value;
      this.ttlNanos = ttlNanos;
    }

    byte[] value() {
      return value;
    }

    long ttlNanos() {
      return ttlNanos;
    }
  }

  private final Cache<String, Entry> cache;

  public CaffeineByteCache(int maxEntries) {
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfter(
                new Expiry<String, Entry>() {
                  @Override
                  public long expireAfterCreate(String key, Entry value, long currentTime) {
                    return value.ttlNanos();
                  }

                  @Override
                  public long expireAfterUpdate(
                      String key, Entry value, long currentTime, long currentDuration) {
                    return value.ttlNanos();
                  }

                  @Override
                  public long expireAfterRead(
                      String key, Entry value, long currentTime, long currentDuration) {
                    return currentDuration;
                  }
                })
            .build();
  }

  @Override
  public Optional<byte[]> get(String key) {
    Entry e = cache.getIfPresent(key);
    return e == null ? Optional.empty() : Optional.of(e.value().clone());
  }

  @Override
  public void put(String key, byte[] value, Duration ttl) {
    cache.put(key, new Entry(value.clone(), ttl.toNanos()));
  }

  @Override
  public void evict(String key) {
    cache.invalidate(key);
  }

  @Override
  public long evictByPrefix(String prefix) {
    long n = cache.asMap().keySet().stream().filter(k -> k.startsWith(prefix)).count();
    cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    return n;
  }
}
