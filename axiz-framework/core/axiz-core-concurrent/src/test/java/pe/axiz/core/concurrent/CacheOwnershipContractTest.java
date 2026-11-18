package pe.axiz.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class CacheOwnershipContractTest {
  private static final class ManualClock extends Clock {
    private Instant now = Instant.EPOCH;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }

  private static AsyncLoadingCache<byte[]> cache(
      Clock clock, long uses, int capacity, List<byte[]> retired) {
    return new AsyncLoadingCache<>(
        Duration.ofSeconds(1),
        uses,
        capacity,
        clock,
        bytes -> true,
        byte[]::clone,
        bytes -> {
          retired.add(bytes);
          Arrays.fill(bytes, (byte) 0);
        });
  }

  @Test
  void expiryUseLimitsEvictionAndCloseEraseOwnedValuesExactlyOnceButPreserveDeliveredSnapshots() {
    var clock = new ManualClock();
    var retired = new ArrayList<byte[]>();
    var hits = new ArrayList<Boolean>();
    var cache = cache(clock, 3, 1, retired);
    byte[] owned = {1, 2};
    byte[] delivered =
        cache
            .get("key", () -> CompletableFuture.completedFuture(owned), hits::add)
            .toCompletableFuture()
            .join();
    clock.now = Instant.EPOCH.plusMillis(999);
    assertArrayEquals(
        owned,
        cache
            .get(
                "key",
                () -> {
                  throw new AssertionError("early expiration");
                },
                hits::add)
            .toCompletableFuture()
            .join());
    clock.now = Instant.EPOCH.plusSeconds(1);
    byte[] replacement = {3, 4};
    assertArrayEquals(
        replacement,
        cache
            .get("key", () -> CompletableFuture.completedFuture(replacement), hits::add)
            .toCompletableFuture()
            .join());
    assertEquals(List.of(false, true, false), hits);
    assertArrayEquals(new byte[2], owned);
    assertArrayEquals(new byte[] {1, 2}, delivered);
    byte[] other = {5, 6};
    cache.get("other", () -> CompletableFuture.completedFuture(other)).toCompletableFuture().join();
    assertArrayEquals(new byte[2], replacement);
    cache.evict("other");
    cache.evict("absent");
    assertArrayEquals(new byte[2], other);
    byte[] finalKey = {7, 8};
    byte[] snapshot =
        cache
            .get("final", () -> CompletableFuture.completedFuture(finalKey))
            .toCompletableFuture()
            .join();
    cache.close();
    cache.close();
    assertArrayEquals(new byte[2], finalKey);
    assertArrayEquals(new byte[] {7, 8}, snapshot);
    assertEquals(4, retired.size());
    var failure =
        assertThrows(
            CompletionException.class,
            () ->
                cache
                    .get("no", () -> CompletableFuture.completedFuture(new byte[] {1}))
                    .toCompletableFuture()
                    .join());
    assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    try (var once = cache(clock, 1, 1, retired)) {
      byte[] first = {9};
      once.get("k", () -> CompletableFuture.completedFuture(first)).toCompletableFuture().join();
      once.get("k", () -> CompletableFuture.completedFuture(new byte[] {10}))
          .toCompletableFuture()
          .join();
      assertArrayEquals(new byte[1], first);
    }
  }

  @Test
  void shutdownCancelsAllPendingLoadsIncludingEvictedOnesAndErasesLateUncancellableResults() {
    var retired = new ArrayList<byte[]>();
    var cache = cache(Clock.systemUTC(), 10, 2, retired);
    var cancellable = new CompletableFuture<byte[]>();
    var late =
        new CompletableFuture<byte[]>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    var first = cache.get("first", () -> cancellable).toCompletableFuture();
    var second = cache.get("second", () -> late).toCompletableFuture();
    cache.evict("second");
    cache.close();
    assertTrue(cancellable.isCancelled());
    assertTrue(first.isCompletedExceptionally());
    assertTrue(second.isCompletedExceptionally());
    assertFalse(late.isDone());
    byte[] material = {1, 2, 3};
    assertTrue(late.complete(material));
    assertArrayEquals(new byte[3], material);
    assertEquals(1, retired.size());
  }

  @Test
  void rejectedValuesAndCancelledLastCallersReleaseMaterialAndObserversCannotChangeResults() {
    var retired = new ArrayList<byte[]>();
    var noCache =
        new AsyncLoadingCache<byte[]>(
            Duration.ZERO,
            1,
            1,
            Clock.systemUTC(),
            bytes -> false,
            byte[]::clone,
            bytes -> {
              retired.add(bytes);
              Arrays.fill(bytes, (byte) 0);
            });
    byte[] input = {9};
    assertArrayEquals(
        new byte[] {9},
        noCache
            .get(
                "key",
                () -> CompletableFuture.completedFuture(input),
                hit -> {
                  throw new IllegalStateException("diagnostics");
                })
            .toCompletableFuture()
            .join());
    assertArrayEquals(new byte[1], input);
    assertEquals(1, retired.size());
    try (var cache = cache(Clock.systemUTC(), 1, 1, retired)) {
      var pending =
          new CompletableFuture<byte[]>() {
            @Override
            public boolean cancel(boolean interrupt) {
              return false;
            }
          };
      var result = cache.get("key", () -> pending).toCompletableFuture();
      assertTrue(result.cancel(true));
      byte[] late = {7};
      assertTrue(pending.complete(late));
      assertArrayEquals(new byte[1], late);
      assertArrayEquals(
          new byte[] {8},
          cache
              .get("next", () -> CompletableFuture.completedFuture(new byte[] {8}))
              .toCompletableFuture()
              .join());
    }
  }
}
