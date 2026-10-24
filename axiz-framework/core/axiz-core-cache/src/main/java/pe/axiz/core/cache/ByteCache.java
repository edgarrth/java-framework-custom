package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Byte-level cache tier (local memory, Redis). Keys already include the service and cache prefix.
 */
public interface ByteCache {

  Optional<byte[]> get(String key);

  /**
   * The value and what the tier knows about its expiration, read together (one round trip). A tier
   * that cannot tell returns {@link CachedValue#unknownExpiry}; {@link TwoLevelCache} then keeps no
   * local copy, so it never serves a value past an expiration it does not know.
   */
  default Optional<CachedValue> getWithTtl(String key) {
    return get(key).map(CachedValue::unknownExpiry);
  }

  /**
   * A reading, in nanoseconds, of the clock this tier expires entries with. Only differences
   * between two readings mean something. {@link TwoLevelCache} times each L2 read with it too, so
   * the copy it keeps here is measured in the same time base that will expire it. The default is
   * the monotonic clock of the JVM, the one Caffeine uses.
   */
  default long ticks() {
    return System.nanoTime();
  }

  /** Stores the value with a TTL applied to the very same key. */
  void put(String key, byte[] value, Duration ttl);

  void evict(String key);

  /** Evicts every key starting with the prefix (SCAN based, never KEYS); returns the count. */
  long evictByPrefix(String prefix);
}
