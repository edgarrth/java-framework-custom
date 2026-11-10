package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AsyncLoadingCacheTest {
  @Test
  void coalescesPendingCallsAndCancelsOnlyAfterTheLastCallerLeaves() {
    var cache = new AsyncLoadingCache<String>(Duration.ofMinutes(1), 10, 2, Clock.systemUTC());
    var nativeRequest = new CompletableFuture<String>();
    var calls = new AtomicInteger();
    Supplier<CompletionStage<String>> load =
        () -> {
          calls.incrementAndGet();
          return nativeRequest;
        };
    var first = cache.get("key", load).toCompletableFuture();
    var second = cache.get("key", load).toCompletableFuture();
    assertEquals(1, calls.get());
    first.cancel(true);
    assertFalse(nativeRequest.isDone());
    second.cancel(false);
    assertTrue(nativeRequest.isCancelled());
    assertEquals(
        "retry",
        cache
            .get("key", () -> CompletableFuture.completedFuture("retry"))
            .toCompletableFuture()
            .join());
  }

  @Test
  void missesAndErrorsAreNotCachedAndExplicitEvictionFencesLateResults() {
    var cache =
        new AsyncLoadingCache<Optional<String>>(
            Duration.ofMinutes(1), Long.MAX_VALUE, 2, Clock.systemUTC(), Optional::isPresent);
    assertTrue(
        cache
            .get("missing", () -> CompletableFuture.completedFuture(Optional.empty()))
            .toCompletableFuture()
            .join()
            .isEmpty());
    assertEquals(
        Optional.of("rotated"),
        cache
            .get("missing", () -> CompletableFuture.completedFuture(Optional.of("rotated")))
            .toCompletableFuture()
            .join());
    var old = new CompletableFuture<Optional<String>>();
    var waiting = cache.get("late", () -> old).toCompletableFuture();
    cache.evict("late");
    assertEquals(
        Optional.of("new"),
        cache
            .get("late", () -> CompletableFuture.completedFuture(Optional.of("new")))
            .toCompletableFuture()
            .join());
    old.complete(Optional.of("old"));
    assertEquals(Optional.of("old"), waiting.join());
    assertEquals(
        Optional.of("new"),
        cache
            .get(
                "late",
                () -> {
                  throw new AssertionError("late completion replaced the new cache entry");
                })
            .toCompletableFuture()
            .join());
    var failed =
        cache
            .get(
                "failure",
                () -> {
                  throw new IllegalStateException("offline");
                })
            .toCompletableFuture();
    assertInstanceOf(
        IllegalStateException.class,
        assertThrows(CompletionException.class, failed::join).getCause());
    assertEquals(
        Optional.of("recovered"),
        cache
            .get("failure", () -> CompletableFuture.completedFuture(Optional.of("recovered")))
            .toCompletableFuture()
            .join());
  }

  @Test
  void maximumUsesLruAndPendingAdmissionAreAllBounded() {
    var cache = new AsyncLoadingCache<String>(Duration.ofMinutes(1), 2, 2, Clock.systemUTC());
    assertEquals(
        "first",
        cache
            .get("key", () -> CompletableFuture.completedFuture("first"))
            .toCompletableFuture()
            .join());
    assertEquals(
        "first",
        cache
            .get(
                "key",
                () -> {
                  throw new AssertionError();
                })
            .toCompletableFuture()
            .join());
    assertEquals(
        "second",
        cache
            .get("key", () -> CompletableFuture.completedFuture("second"))
            .toCompletableFuture()
            .join());
    var pendingA = new CompletableFuture<String>();
    var pendingB = new CompletableFuture<String>();
    var a = cache.get("a", () -> pendingA).toCompletableFuture();
    cache.get("b", () -> pendingB);
    var overflow =
        cache
            .get("overflow", () -> CompletableFuture.completedFuture("overflow"))
            .toCompletableFuture();
    assertInstanceOf(
        RejectedExecutionException.class,
        assertThrows(CompletionException.class, overflow::join).getCause());
    a.cancel(true);
    assertEquals(
        "admitted",
        cache
            .get("overflow", () -> CompletableFuture.completedFuture("admitted"))
            .toCompletableFuture()
            .join());
    pendingB.complete("done");
    assertEquals(
        "evicted",
        cache
            .get("key", () -> CompletableFuture.completedFuture("evicted"))
            .toCompletableFuture()
            .join());
  }

  @Test
  void zeroTtlDoesNotRetainValuesAndInvalidConfigurationIsRejected() {
    var clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    var cache = new AsyncLoadingCache<String>(Duration.ZERO, 10, 2, clock);
    assertEquals(
        "one",
        cache
            .get("key", () -> CompletableFuture.completedFuture("one"))
            .toCompletableFuture()
            .join());
    assertEquals(
        "two",
        cache
            .get("key", () -> CompletableFuture.completedFuture("two"))
            .toCompletableFuture()
            .join());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncLoadingCache<String>(Duration.ofSeconds(-1), 1, 1, clock));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncLoadingCache<String>(Duration.ZERO, 0, 1, clock));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncLoadingCache<String>(Duration.ZERO, 1, 0, clock));
  }
}
