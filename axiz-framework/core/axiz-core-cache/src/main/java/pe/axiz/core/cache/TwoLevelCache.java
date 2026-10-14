package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 (local) + L2 (Redis) cache. Reads go L1 then L2 (filling L1 with a shorter TTL); writes and
 * evictions go to both. A failing L2 never fails the caller: the error is logged and the call
 * continues as a cache miss (the source of truth is used).
 */
public final class TwoLevelCache implements ByteCache {

  private static final Logger LOG = LoggerFactory.getLogger(TwoLevelCache.class);

  private final ByteCache l1;
  private final ByteCache l2;
  private final Duration l1MaxTtl;

  public TwoLevelCache(ByteCache l1, ByteCache l2, Duration l1MaxTtl) {
    this.l1 = Objects.requireNonNull(l1);
    this.l2 = Objects.requireNonNull(l2);
    this.l1MaxTtl = Objects.requireNonNull(l1MaxTtl);
  }

  @Override
  public Optional<byte[]> get(String key) {
    Optional<byte[]> local = l1.get(key);
    if (local.isPresent()) {
      return local;
    }
    try {
      Optional<byte[]> remote = l2.get(key);
      remote.ifPresent(v -> l1.put(key, v, l1MaxTtl));
      return remote;
    } catch (RuntimeException e) {
      LOG.warn("L2 cache read failed for a key; continuing without cache: {}", e.toString());
      return Optional.empty();
    }
  }

  @Override
  public void put(String key, byte[] value, Duration ttl) {
    l1.put(key, value, ttl.compareTo(l1MaxTtl) < 0 ? ttl : l1MaxTtl);
    try {
      l2.put(key, value, ttl);
    } catch (RuntimeException e) {
      LOG.warn("L2 cache write failed; value kept only locally: {}", e.toString());
    }
  }

  @Override
  public void evict(String key) {
    l1.evict(key);
    try {
      l2.evict(key);
    } catch (RuntimeException e) {
      LOG.warn("L2 cache eviction failed: {}", e.toString());
    }
  }

  @Override
  public long evictByPrefix(String prefix) {
    long n = l1.evictByPrefix(prefix);
    try {
      n += l2.evictByPrefix(prefix);
    } catch (RuntimeException e) {
      LOG.warn("L2 cache prefix eviction failed: {}", e.toString());
    }
    return n;
  }
}
