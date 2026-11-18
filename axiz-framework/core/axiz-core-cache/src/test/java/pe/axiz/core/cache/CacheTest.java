package pe.axiz.core.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheTest {

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Codec for tests: strings and lists of strings separated by commas. */
  static final class TextCodec implements ValueCodec {
    @Override
    public byte[] encode(Object value) {
      String s =
          value instanceof List<?> l
              ? "L:" + String.join(",", l.stream().map(Object::toString).toList())
              : "S:" + value;
      return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T decode(byte[] bytes, Class<T> type) {
      String s = new String(bytes, StandardCharsets.UTF_8);
      if (s.startsWith("L:")) {
        return type.cast(s.length() == 2 ? List.of() : List.of(s.substring(2).split(",")));
      }
      if (!s.startsWith("S:")) {
        throw new IllegalArgumentException("corrupt");
      }
      return type.cast(s.substring(2));
    }
  }

  /** L2 that can be switched off to simulate a Redis outage. */
  static final class FlakyCache implements ByteCache {
    final InMemoryByteCache delegate;
    boolean down;

    FlakyCache(Clock clock) {
      delegate = new InMemoryByteCache(100, clock);
    }

    private void check() {
      if (down) {
        throw new CacheUnavailableException("redis down", new RuntimeException());
      }
    }

    @Override
    public Optional<byte[]> get(String key) {
      check();
      return delegate.get(key);
    }

    /** Like Redis: the value and its remaining time in one read. */
    @Override
    public Optional<CachedValue> getWithTtl(String key) {
      check();
      return delegate.getWithTtl(key);
    }

    @Override
    public void put(String key, byte[] value, Duration ttl) {
      check();
      delegate.put(key, value, ttl);
    }

    @Override
    public void evict(String key) {
      check();
      delegate.evict(key);
    }

    @Override
    public long evictByPrefix(String prefix) {
      check();
      return delegate.evictByPrefix(prefix);
    }
  }

  /** A tier that keeps the default {@code getWithTtl}: it cannot tell when an entry expires. */
  static final class NoTtlCache implements ByteCache {
    final InMemoryByteCache delegate;

    NoTtlCache(Clock clock) {
      delegate = new InMemoryByteCache(100, clock);
    }

    @Override
    public Optional<byte[]> get(String key) {
      return delegate.get(key);
    }

    @Override
    public void put(String key, byte[] value, Duration ttl) {
      delegate.put(key, value, ttl);
    }

    @Override
    public void evict(String key) {
      delegate.evict(key);
    }

    @Override
    public long evictByPrefix(String prefix) {
      return delegate.evictByPrefix(prefix);
    }
  }

  @Test
  void evictionReachesBothTiersAndPrefixCountsDoNotIncludeOtherNamespaces() {
    var clock = new MutableClock();
    var local = new InMemoryByteCache(10, clock);
    var remote = new FlakyCache(clock);
    var cache = new TwoLevelCache(local, remote, Duration.ofMinutes(1));
    for (String key : List.of("group:one", "group:two", "other"))
      cache.put(key, new byte[] {1}, Duration.ofMinutes(1));
    cache.evict("group:one");
    assertTrue(local.get("group:one").isEmpty());
    assertTrue(remote.get("group:one").isEmpty());
    assertEquals(2, cache.evictByPrefix("group:"));
    assertTrue(local.get("group:two").isEmpty());
    assertTrue(remote.get("group:two").isEmpty());
    assertTrue(local.get("other").isPresent());
    assertTrue(remote.get("other").isPresent());
    remote.down = true;
    assertEquals(1, cache.evictByPrefix("other"));
    assertTrue(local.get("other").isEmpty());
  }

  @Test
  void typedEvictionPreservesTheOtherCacheAndCacheHitsNeverInvokeTheLoader() {
    var bytes = new InMemoryByteCache(10, Clock.systemUTC());
    var first = new AxizCache("payments", "first", bytes, new TextCodec(), Duration.ofMinutes(1));
    var second = new AxizCache("payments", "second", bytes, new TextCodec(), Duration.ofMinutes(1));
    first.put("key", "first-value");
    second.put("key", "second-value");
    assertEquals(
        "first-value",
        first.getOrLoad(
            "key",
            String.class,
            () -> {
              throw new AssertionError("A hit must not call the origin");
            }));
    first.evict("key");
    assertTrue(first.get("key", String.class).isEmpty());
    assertEquals("second-value", second.get("key", String.class).orElseThrow());
  }

  @Test
  void inMemoryTtlAndBound() {
    MutableClock clock = new MutableClock();
    InMemoryByteCache c = new InMemoryByteCache(2, clock);
    c.put("a", new byte[] {1}, Duration.ofSeconds(10));
    c.put("b", new byte[] {2}, Duration.ofSeconds(20));
    c.put("c", new byte[] {3}, Duration.ofSeconds(30));
    assertEquals(2, c.size());
    assertTrue(c.get("a").isEmpty(), "oldest-expiring entry evicted");
    clock.now = clock.now.plusSeconds(25);
    assertTrue(c.get("b").isEmpty());
    assertArrayEquals(new byte[] {3}, c.get("c").orElseThrow());
    assertEquals(1, c.evictByPrefix("c"));
    assertThrows(IllegalArgumentException.class, () -> new InMemoryByteCache(0, clock));
  }

  @Test
  void twoLevelReadsThroughAndToleratesL2Failures() {
    MutableClock clock = new MutableClock();
    InMemoryByteCache l1 = new InMemoryByteCache(100, clock);
    FlakyCache l2 = new FlakyCache(clock);
    TwoLevelCache cache = new TwoLevelCache(l1, l2, Duration.ofSeconds(30));
    l2.delegate.put("k", new byte[] {9}, Duration.ofMinutes(5));
    assertArrayEquals(new byte[] {9}, cache.get("k").orElseThrow());
    assertTrue(l1.get("k").isPresent(), "L1 filled from L2");
    l2.down = true;
    cache.put("x", new byte[] {1}, Duration.ofMinutes(10));
    assertTrue(cache.get("x").isPresent(), "served from L1 while L2 is down");
    clock.now = clock.now.plusSeconds(31);
    assertTrue(cache.get("x").isEmpty(), "L1 TTL is capped; L2 failure is a miss, not an error");
    cache.evict("x");
    cache.evictByPrefix("x");
  }

  @Test
  void typedCacheSingleFlightAndCollections() throws Exception {
    MutableClock clock = new MutableClock();
    AxizCache cache =
        new AxizCache(
            "payments",
            "beneficiaries",
            new InMemoryByteCache(100, clock),
            new TextCodec(),
            Duration.ofMinutes(5));
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<Future<String>> answers = new ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 20; i++) {
        answers.add(
            executor.submit(
                () -> {
                  start.await();
                  return cache.getOrLoad(
                      "c1",
                      String.class,
                      () -> {
                        loads.incrementAndGet();
                        loading.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("loader not released");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return "value";
                      });
                }));
      }
      start.countDown();
      try {
        assertTrue(loading.await(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      for (Future<String> answer : answers) assertEquals("value", answer.get(5, TimeUnit.SECONDS));
    }
    assertEquals(1, loads.get());
    assertEquals("value", cache.get("c1", String.class).orElseThrow());
    assertEquals(List.of("only"), cache.getOrLoad("single", List.class, () -> List.of("only")));
    assertEquals(List.of("only"), cache.get("single", List.class).orElseThrow());
    assertNull(cache.getOrLoad("missing", String.class, () -> null));
    assertTrue(cache.get("missing", String.class).isEmpty(), "null results are not cached");
    assertEquals("payments:beneficiaries:k", cache.fullKey("k"));
    assertEquals(2, cache.clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizCache(
                "p", "Bad Name", new InMemoryByteCache(1, clock), new TextCodec(), Duration.ZERO));
  }

  @Test
  void aDelayedMissCannotReloadAValueAnotherCallerAlreadyPublished() throws Exception {
    var delegate = new InMemoryByteCache(10, Clock.systemUTC());
    CountDownLatch missed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    ByteCache staggered =
        new ByteCache() {
          @Override
          public Optional<byte[]> get(String key) {
            Optional<byte[]> snapshot = delegate.get(key);
            if (reads.incrementAndGet() == 1) {
              missed.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("stale read not released");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            }
            return snapshot;
          }

          @Override
          public void put(String key, byte[] value, Duration ttl) {
            delegate.put(key, value, ttl);
          }

          @Override
          public void evict(String key) {
            delegate.evict(key);
          }

          @Override
          public long evictByPrefix(String prefix) {
            return delegate.evictByPrefix(prefix);
          }
        };
    var cache =
        new AxizCache("payments", "test", staggered, new TextCodec(), Duration.ofMinutes(1));
    AtomicInteger loads = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> delayed =
          executor.submit(
              () ->
                  cache.getOrLoad(
                      "key",
                      String.class,
                      () -> {
                        loads.incrementAndGet();
                        return "duplicate";
                      }));
      try {
        assertTrue(missed.await(5, TimeUnit.SECONDS));
        assertEquals(
            "fresh",
            cache.getOrLoad(
                "key",
                String.class,
                () -> {
                  loads.incrementAndGet();
                  return "fresh";
                }));
      } finally {
        release.countDown();
      }
      assertEquals("fresh", delayed.get(5, TimeUnit.SECONDS));
    }
    assertEquals(1, loads.get());
  }

  @Test
  void corruptEntriesAreDiscarded() {
    MutableClock clock = new MutableClock();
    InMemoryByteCache store = new InMemoryByteCache(10, clock);
    AxizCache cache = new AxizCache("svc", "c", store, new TextCodec(), Duration.ofMinutes(1));
    store.put(
        cache.fullKey("k"), "garbage".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(1));
    assertTrue(cache.get("k", String.class).isEmpty());
    assertTrue(store.get(cache.fullKey("k")).isEmpty());
    assertThrows(
        IllegalStateException.class,
        () ->
            cache.getOrLoad(
                "e",
                String.class,
                () -> {
                  throw new IllegalStateException("source down");
                }));
  }

  @Test
  void keysEscapeSeparatorsAndKeepTypes() {
    assertEquals("s=a:s=b\\:c:-", CacheKeys.of("a", "b:c", null));
  }

  @Test
  void aCopyTakenFromL2NeverOutlivesTheOriginalExpiration() {
    MutableClock clock = new MutableClock();
    InMemoryByteCache l1 = new InMemoryByteCache(10, clock);
    InMemoryByteCache l2 = new InMemoryByteCache(10, clock);
    TwoLevelCache cache = new TwoLevelCache(l1, l2, Duration.ofSeconds(3));
    cache.put("rate", "old".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10));
    l1.evict("rate"); // another instance: only L2 has it
    clock.now = clock.now.plusSeconds(9);
    assertTrue(cache.get("rate").isPresent());
    clock.now = clock.now.plusSeconds(2);
    assertTrue(l2.get("rate").isEmpty());
    assertTrue(cache.get("rate").isEmpty(), "L1 must not serve past the 10 second TTL");
  }

  @Test
  void aCopyLivesTheL1LimitWhenL2HasMoreTimeLeftAndNoneWhenItCannotTell() {
    MutableClock clock = new MutableClock();
    InMemoryByteCache l1 = new InMemoryByteCache(10, clock);
    InMemoryByteCache l2 = new InMemoryByteCache(10, clock);
    TwoLevelCache cache = new TwoLevelCache(l1, l2, Duration.ofSeconds(3));
    l2.put("k", new byte[] {1}, Duration.ofMinutes(5));
    assertTrue(cache.get("k").isPresent());
    l2.evict("k");
    clock.now = clock.now.plusSeconds(2);
    assertTrue(cache.get("k").isPresent(), "within the L1 limit");
    clock.now = clock.now.plusSeconds(2);
    assertTrue(cache.get("k").isEmpty(), "past the L1 limit");

    NoTtlCache unknown = new NoTtlCache(clock); // cannot tell the expiration: no local copy
    InMemoryByteCache otherL1 = new InMemoryByteCache(10, clock);
    TwoLevelCache other = new TwoLevelCache(otherL1, unknown, Duration.ofSeconds(3));
    unknown.put("u", new byte[] {2}, Duration.ofMinutes(5));
    assertTrue(other.get("u").isPresent(), "the value is still returned");
    assertTrue(otherL1.get("u").isEmpty(), "but never copied without a known expiration");
    assertTrue(other.getWithTtl("u").orElseThrow().remaining().isEmpty());
  }

  /** An L2 that answers with a fixed expiration while the read takes a controlled time. */
  static final class SlowL2 implements ByteCache {
    final long[] nanos;
    final long readNanos;
    final CachedValue answer;

    SlowL2(long[] nanos, long readNanos, CachedValue answer) {
      this.nanos = nanos;
      this.readNanos = readNanos;
      this.answer = answer;
    }

    @Override
    public Optional<CachedValue> getWithTtl(String key) {
      nanos[0] += readNanos; // the answer arrives this much later
      return Optional.of(answer);
    }

    @Override
    public Optional<byte[]> get(String key) {
      return Optional.of(answer.value());
    }

    @Override
    public void put(String key, byte[] value, Duration ttl) {}

    @Override
    public void evict(String key) {}

    @Override
    public long evictByPrefix(String prefix) {
      return 0;
    }
  }

  @Test
  void aSlowAnswerFromL2CannotStretchTheExpiration() {
    MutableClock clock = new MutableClock();
    long[] nanos = {0};
    InMemoryByteCache l1 = new InMemoryByteCache(10, clock);
    // L2 had 1 s left when it read the key, the answer took 1.5 s to arrive: nothing to copy
    TwoLevelCache late =
        new TwoLevelCache(
            l1,
            new SlowL2(
                nanos,
                1_500_000_000L,
                CachedValue.expiringIn(new byte[] {1}, Duration.ofSeconds(1))),
            Duration.ofSeconds(30),
            () -> nanos[0]);
    assertTrue(late.get("k").isPresent(), "the value was valid when L2 read it");
    assertTrue(l1.get("k").isEmpty(), "an answer with no time left is not copied");

    // 10 s left, the read took 4 s: the copy lives 6 s, not 10
    InMemoryByteCache l1b = new InMemoryByteCache(10, clock);
    TwoLevelCache slow =
        new TwoLevelCache(
            l1b,
            new SlowL2(
                nanos,
                4_000_000_000L,
                CachedValue.expiringIn(new byte[] {2}, Duration.ofSeconds(10))),
            Duration.ofSeconds(30),
            () -> nanos[0]);
    assertTrue(slow.get("k").isPresent());
    clock.now = clock.now.plusSeconds(5);
    assertTrue(l1b.get("k").isPresent());
    clock.now = clock.now.plusSeconds(2);
    assertTrue(l1b.get("k").isEmpty(), "the copy expired with the original, read time included");
  }

  @Test
  void aDelayMeasuredByTheClockOfL1AlsoCounts() {
    MutableClock clock = new MutableClock();
    InMemoryByteCache l1 = new InMemoryByteCache(10, clock);
    InMemoryByteCache store = new InMemoryByteCache(10, clock);
    ByteCache delayed =
        new ByteCache() {
          @Override
          public Optional<CachedValue> getWithTtl(String key) {
            Optional<CachedValue> measured = store.getWithTtl(key);
            clock.now = clock.now.plusSeconds(2); // the answer arrives 2 s after L2 measured it
            return measured;
          }

          @Override
          public Optional<byte[]> get(String key) {
            return store.get(key);
          }

          @Override
          public void put(String key, byte[] value, Duration ttl) {
            store.put(key, value, ttl);
          }

          @Override
          public void evict(String key) {
            store.evict(key);
          }

          @Override
          public long evictByPrefix(String prefix) {
            return store.evictByPrefix(prefix);
          }
        };
    // the production constructor: only the L1 clock sees the delay
    TwoLevelCache cache = new TwoLevelCache(l1, delayed, Duration.ofSeconds(3));
    cache.put("k", new byte[] {1}, Duration.ofSeconds(10));
    clock.now = clock.now.plusSeconds(9);
    assertTrue(cache.get("k").isPresent(), "valid when L2 read it, at 9 s");
    clock.now = clock.now.plusMillis(500);
    assertTrue(store.get("k").isEmpty());
    assertTrue(cache.get("k").isEmpty(), "at 11.5 s the copy may not outlive the 10 s deadline");
    assertEquals(l1.ticks(), cache.ticks(), "local copies expire by the L1 clock");
  }

  @Test
  void anEntryWithoutExpirationIsCopiedForTheL1Limit() {
    MutableClock clock = new MutableClock();
    long[] nanos = {0};
    InMemoryByteCache l1 = new InMemoryByteCache(10, clock);
    TwoLevelCache cache =
        new TwoLevelCache(
            l1,
            new SlowL2(nanos, 0, CachedValue.withoutExpiry(new byte[] {3})),
            Duration.ofSeconds(3),
            () -> nanos[0]);
    assertTrue(cache.get("k").isPresent());
    clock.now = clock.now.plusSeconds(2);
    assertTrue(l1.get("k").isPresent());
    clock.now = clock.now.plusSeconds(2);
    assertTrue(l1.get("k").isEmpty());
  }

  @Test
  void theInMemoryTierReportsWhatIsLeft() {
    MutableClock clock = new MutableClock();
    InMemoryByteCache cache = new InMemoryByteCache(10, clock);
    assertTrue(cache.getWithTtl("none").isEmpty());
    cache.put("k", new byte[] {7}, Duration.ofSeconds(10));
    clock.now = clock.now.plusSeconds(4);
    CachedValue v = cache.getWithTtl("k").orElseThrow();
    assertEquals(Duration.ofSeconds(6), v.remaining().orElseThrow());
    byte[] copy = v.value();
    copy[0] = 0;
    assertEquals(7, v.value()[0], "defensive copy");
    clock.now = clock.now.plusSeconds(6);
    assertTrue(cache.getWithTtl("k").isEmpty());
  }
}
