package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Byte-level cache tier (local memory, Redis). Keys already include the service and cache prefix.
 */
public interface ByteCache {

  Optional<byte[]> get(String key);

  /**
   * The value and the time it still has to live, read together. A tier that cannot tell returns an
   * unknown remaining time; {@link TwoLevelCache} then keeps a local copy for at most its L1 limit.
   */
  default Optional<CachedValue> getWithTtl(String key) {
    return get(key).map(v -> new CachedValue(v, null));
  }

  /** Stores the value with a TTL applied to the very same key. */
  void put(String key, byte[] value, Duration ttl);

  void evict(String key);

  /** Evicts every key starting with the prefix (SCAN based, never KEYS); returns the count. */
  long evictByPrefix(String prefix);
}
