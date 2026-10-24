package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 (local) + L2 (Redis) cache. Reads go L1 then L2; writes and evictions go to both. A failing L2
 * never fails the caller: the error is logged and the call continues as a cache miss (the source of
 * truth is used).
 *
 * <p>Freshness: a local copy never outlives the entry it was taken from. It lives for the time L2
 * reported the entry still had, minus the whole duration of that read, capped by the L1 limit. The
 * read is timed with the monotonic clock and with the clock L1 expires entries by ({@link
 * ByteCache#ticks()}), and the longer of the two counts, so a slow answer cannot stretch the
 * expiration in either time base. An answer that arrives with no time left is returned but not
 * copied. An L2 entry without expiration is copied for the L1 limit; a tier that cannot tell the
 * expiration gets no local copy at all. What L1 may still serve after a change elsewhere is bounded
 * by the L1 limit: an eviction on another instance reaches this L1 only through the eviction of the
 * same key here, or when the copy expires.
 */
public final class TwoLevelCache implements ByteCache {

  private static final Logger LOG = LoggerFactory.getLogger(TwoLevelCache.class);

  private final ByteCache l1;
  private final ByteCache l2;
  private final Duration l1MaxTtl;
  private final LongSupplier nanoTime;

  public TwoLevelCache(ByteCache l1, ByteCache l2, Duration l1MaxTtl) {
    this(l1, l2, l1MaxTtl, System::nanoTime);
  }

  /**
   * With the monotonic clock that times the L2 reads, in nanoseconds ({@code System::nanoTime} in
   * production; a controlled one in tests).
   */
  public TwoLevelCache(ByteCache l1, ByteCache l2, Duration l1MaxTtl, LongSupplier nanoTime) {
    this.l1 = Objects.requireNonNull(l1);
    this.l2 = Objects.requireNonNull(l2);
    this.l1MaxTtl = Objects.requireNonNull(l1MaxTtl);
    this.nanoTime = Objects.requireNonNull(nanoTime);
  }

  @Override
  public Optional<byte[]> get(String key) {
    Optional<byte[]> local = l1.get(key);
    if (local.isPresent()) {
      return local;
    }
    try {
      long started = nanoTime.getAsLong();
      long localStarted = l1.ticks();
      Optional<CachedValue> remote = l2.getWithTtl(key);
      if (remote.isEmpty()) {
        return Optional.empty();
      }
      long took = Math.max(nanoTime.getAsLong() - started, l1.ticks() - localStarted);
      Duration readTook = Duration.ofNanos(Math.max(0, took));
      Duration forL1 = localTtl(remote.get(), readTook);
      if (forL1.isPositive()) {
        l1.put(key, remote.get().value(), forL1);
      }
      return Optional.of(remote.get().value());
    } catch (RuntimeException e) {
      LOG.warn("L2 cache read failed for a key; continuing without cache: {}", e.toString());
      return Optional.empty();
    }
  }

  /**
   * How long the local copy may live: the time L2 had left minus the duration of the read (L2
   * measured it at some point of that read, so the whole read is discounted), capped by the L1
   * limit; zero when the expiration is unknown.
   */
  private Duration localTtl(CachedValue remote, Duration readTook) {
    if (!remote.expiryKnown()) {
      return Duration.ZERO;
    }
    if (remote.neverExpires()) {
      return l1MaxTtl;
    }
    return capped(remote.remaining().orElse(Duration.ZERO).minus(readTook));
  }

  private Duration capped(Duration remaining) {
    return remaining.compareTo(l1MaxTtl) < 0 ? remaining : l1MaxTtl;
  }

  /** Local copies expire by the L1 clock. */
  @Override
  public long ticks() {
    return l1.ticks();
  }

  @Override
  public void put(String key, byte[] value, Duration ttl) {
    l1.put(key, value, capped(ttl));
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
