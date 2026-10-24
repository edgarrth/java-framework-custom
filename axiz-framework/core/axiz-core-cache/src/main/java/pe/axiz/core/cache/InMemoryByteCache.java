package pe.axiz.core.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local cache tier with TTL and a size bound (oldest entries are dropped first when full). Used as
 * L1 when Caffeine is not on the classpath and in tests.
 */
public final class InMemoryByteCache implements ByteCache {

  private final Map<String, Entry> map = new ConcurrentHashMap<>();
  private final int maxEntries;
  private final Clock clock;

  public InMemoryByteCache(int maxEntries, Clock clock) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be positive");
    }
    this.maxEntries = maxEntries;
    this.clock = clock;
  }

  @Override
  public Optional<byte[]> get(String key) {
    Entry e = map.get(key);
    if (e == null) {
      return Optional.empty();
    }
    if (!clock.instant().isBefore(e.expiresAt)) {
      map.remove(key, e);
      return Optional.empty();
    }
    return Optional.of(e.value.clone());
  }

  @Override
  public Optional<CachedValue> getWithTtl(String key) {
    Entry e = map.get(key);
    Instant now = clock.instant();
    if (e == null) {
      return Optional.empty();
    }
    if (!now.isBefore(e.expiresAt)) {
      map.remove(key, e);
      return Optional.empty();
    }
    return Optional.of(CachedValue.expiringIn(e.value, Duration.between(now, e.expiresAt)));
  }

  /** The clock that expires the entries, in nanoseconds. */
  @Override
  public long ticks() {
    Instant now = clock.instant();
    return now.getEpochSecond() * 1_000_000_000L + now.getNano();
  }

  @Override
  public void put(String key, byte[] value, Duration ttl) {
    if (map.size() >= maxEntries && !map.containsKey(key)) {
      evictOldest();
    }
    map.put(key, new Entry(value.clone(), clock.instant().plus(ttl)));
  }

  private void evictOldest() {
    Instant now = clock.instant();
    map.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt));
    if (map.size() < maxEntries) {
      return;
    }
    String oldest = null;
    Instant oldestExpiry = Instant.MAX;
    for (Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, Entry> e = it.next();
      if (e.getValue().expiresAt.isBefore(oldestExpiry)) {
        oldestExpiry = e.getValue().expiresAt;
        oldest = e.getKey();
      }
    }
    if (oldest != null) {
      map.remove(oldest);
    }
  }

  @Override
  public void evict(String key) {
    map.remove(key);
  }

  @Override
  public long evictByPrefix(String prefix) {
    long before = map.size();
    map.keySet().removeIf(k -> k.startsWith(prefix));
    return before - map.size();
  }

  public int size() {
    return map.size();
  }

  /** Internal holder; a class rather than a record because it holds an array. */
  private static final class Entry {
    private final byte[] value;
    private final Instant expiresAt;

    Entry(byte[] value, Instant expiresAt) {
      this.value = value;
      this.expiresAt = expiresAt;
    }
  }
}
