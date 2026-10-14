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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheTest {

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
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
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 20; i++) {
        executor.submit(
            () -> {
              start.await();
              return cache.getOrLoad(
                  "c1",
                  String.class,
                  () -> {
                    loads.incrementAndGet();
                    try {
                      Thread.sleep(50);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    return "value";
                  });
            });
      }
      start.countDown();
    }
    assertTrue(loads.get() <= 2, "loads=" + loads.get());
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
  void keysEscapeSeparators() {
    assertEquals("a:b\\:c:_", CacheKeys.of("a", "b:c", null));
  }
}
