package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Byte-level cache tier (local memory, Redis). Keys already include the service and cache prefix.
 */
public interface ByteCache {

  Optional<byte[]> get(String key);

  /** Stores the value with a TTL applied to the very same key. */
  void put(String key, byte[] value, Duration ttl);

  void evict(String key);

  /** Evicts every key starting with the prefix (SCAN based, never KEYS); returns the count. */
  long evictByPrefix(String prefix);
}
