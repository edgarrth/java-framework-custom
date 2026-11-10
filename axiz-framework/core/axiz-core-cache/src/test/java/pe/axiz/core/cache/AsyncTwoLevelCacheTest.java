package pe.axiz.core.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AsyncTwoLevelCacheTest {
  static final byte[] VALUE = {1, 2, 3};
  static final Duration TTL = Duration.ofSeconds(5);

  static final class Remote implements AsyncByteCache {
    CompletableFuture<Optional<CachedValue>> read = new CompletableFuture<>();
    boolean down;

    @Override
    public CompletionStage<Optional<CachedValue>> getWithTtl(String key) {
      check();
      return read;
    }

    @Override
    public CompletionStage<Optional<byte[]>> get(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<Void> put(String key, byte[] value, Duration ttl) {
      check();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> evict(String key) {
      check();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Long> evictByPrefix(String prefix) {
      check();
      return CompletableFuture.completedFuture(2L);
    }

    private void check() {
      if (down) throw new IllegalStateException("remote offline");
    }
  }

  @Test
  void slowReadDiscountsBothClocksAndReturnedTtl() throws Exception {
    CacheTest.MutableClock clock = new CacheTest.MutableClock();
    var local = new InMemoryByteCache(10, clock);
    var remote = new Remote();
    AtomicLong nanos = new AtomicLong();
    var cache = new AsyncTwoLevelCache(local, remote, TTL, nanos::get);
    var result = cache.getWithTtl("key");
    clock.now = clock.now.plusSeconds(2);
    nanos.set(Duration.ofSeconds(1).toNanos());
    remote.read.complete(Optional.of(CachedValue.expiringIn(VALUE, Duration.ofSeconds(3))));
    assertEquals(
        Duration.ofSeconds(1),
        result.toCompletableFuture().get().orElseThrow().remaining().orElseThrow());
    assertEquals(
        Duration.ofSeconds(1), local.getWithTtl("key").orElseThrow().remaining().orElseThrow());
    assertArrayEquals(VALUE, cache.get("key").toCompletableFuture().get().orElseThrow());
    clock.now = clock.now.plusSeconds(1);
    assertTrue(local.get("key").isEmpty(), "a slow read must never extend remote freshness");
  }

  @Test
  void unknownExpiredAndUnboundedEntriesHaveDistinctLocalPolicies() throws Exception {
    for (CachedValue value :
        List.of(
            CachedValue.unknownExpiry(VALUE),
            CachedValue.expiringIn(VALUE, Duration.ZERO),
            CachedValue.withoutExpiry(VALUE))) {
      var clock = new CacheTest.MutableClock();
      var local = new InMemoryByteCache(10, clock);
      var remote = new Remote();
      remote.read.complete(Optional.of(value));
      var cache = new AsyncTwoLevelCache(local, remote, TTL, () -> 0);
      assertArrayEquals(VALUE, cache.get("key").toCompletableFuture().get().orElseThrow());
      assertEquals(value.neverExpires(), local.get("key").isPresent());
      clock.now = clock.now.plus(TTL);
      assertTrue(local.get("key").isEmpty());
    }
  }

  @Test
  void cancellationReachesNativeReadAndDoesNotPopulateL1() {
    var local = new InMemoryByteCache(10, Clock.systemUTC());
    var remote = new Remote();
    var cache = new AsyncTwoLevelCache(local, remote, TTL);
    var result = cache.get("key").toCompletableFuture();
    result.cancel(true);
    assertTrue(remote.read.isCancelled());
    assertTrue(local.get("key").isEmpty());
  }

  @Test
  void remoteFailureIsAMissAndLocalDataSurvivesWriteFailure() throws Exception {
    var local = new InMemoryByteCache(10, Clock.systemUTC());
    var remote = new Remote();
    var cache = new AsyncTwoLevelCache(local, remote, TTL);
    remote.read.completeExceptionally(new IllegalStateException("timeout"));
    assertTrue(cache.get("missing").toCompletableFuture().get().isEmpty());
    remote.down = true;
    assertTrue(cache.get("submission-error").toCompletableFuture().get().isEmpty());
    cache.put("orders:1", VALUE, Duration.ofSeconds(10)).toCompletableFuture().get();
    assertArrayEquals(VALUE, cache.get("orders:1").toCompletableFuture().get().orElseThrow());
    assertEquals(1L, cache.evictByPrefix("orders:").toCompletableFuture().get());
    cache.put("one", VALUE, TTL).toCompletableFuture().get();
    cache.evict("one").toCompletableFuture().get();
    assertTrue(local.get("one").isEmpty());
    remote.down = false;
    assertEquals(2L, cache.evictByPrefix("orders:").toCompletableFuture().get());
    cache.put("two", VALUE, TTL).toCompletableFuture().get();
    cache.evict("two").toCompletableFuture().get();
  }

  @Test
  void localOnlyNeedsNoRemoteAndRemoteMissStaysAMiss() throws Exception {
    var local = new InMemoryByteCache(10, Clock.systemUTC());
    var cache = new AsyncTwoLevelCache(local, null, TTL);
    assertTrue(cache.get("missing").toCompletableFuture().get().isEmpty());
    cache.put("one", VALUE, TTL).toCompletableFuture().get();
    assertEquals(1L, cache.evictByPrefix("o").toCompletableFuture().get());
    cache.put("two", VALUE, TTL).toCompletableFuture().get();
    cache.evict("two").toCompletableFuture().get();
    assertTrue(cache.get("two").toCompletableFuture().get().isEmpty());
    var remote = new Remote();
    remote.read.complete(Optional.empty());
    assertTrue(
        new AsyncTwoLevelCache(local, remote, TTL)
            .get("absent")
            .toCompletableFuture()
            .get()
            .isEmpty());
  }
}
