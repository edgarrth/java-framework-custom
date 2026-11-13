package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecretCacheObservationTest {
  static final class Measurements implements SecretCacheObserver {
    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger misses = new AtomicInteger();
    final AtomicInteger found = new AtomicInteger();
    final AtomicInteger absent = new AtomicInteger();
    final AtomicInteger failed = new AtomicInteger();

    @Override
    public void hit() {
      hits.incrementAndGet();
    }

    @Override
    public void miss() {
      misses.incrementAndGet();
    }

    @Override
    public void loaded(LoadOutcome outcome, Duration elapsed) {
      assertFalse(elapsed.isNegative());
      AtomicInteger counter =
          switch (outcome) {
            case FOUND -> found;
            case ABSENT -> absent;
            case FAILED -> failed;
          };
      counter.incrementAndGet();
    }
  }

  @Test
  void concurrentNativeMissesShareOneMeasuredLoad() throws Exception {
    var metrics = new Measurements();
    var load = new CompletableFuture<Optional<SecretValue>>();
    var calls = new AtomicInteger();
    var cache =
        new CachingAsyncSecretResolver(
            name -> {
              calls.incrementAndGet();
              return load;
            },
            Duration.ofMinutes(1),
            Clock.systemUTC(),
            metrics);
    var listeners = new ArrayList<CompletionStage<Optional<SecretValue>>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var submissions =
          new ArrayList<java.util.concurrent.Future<CompletionStage<Optional<SecretValue>>>>();
      for (int i = 0; i < 20; i++) submissions.add(executor.submit(() -> cache.resolve("shared")));
      for (var submission : submissions) listeners.add(submission.get(5, TimeUnit.SECONDS));
    }
    assertEquals(1, calls.get());
    assertEquals(20, metrics.misses.get());
    assertEquals(0, metrics.found.get());
    load.complete(Optional.of(SecretValue.of("value")));
    for (var listener : listeners)
      assertTrue(listener.toCompletableFuture().get(5, TimeUnit.SECONDS).isPresent());
    assertEquals(1, metrics.found.get());
    assertTrue(cache.resolve("shared").toCompletableFuture().get(5, TimeUnit.SECONDS).isPresent());
    assertEquals(1, metrics.hits.get());
  }

  @Test
  void failuresAndAbsencesAreDifferentFromMisses() {
    var metrics = new Measurements();
    var cache =
        new CachingAsyncSecretResolver(
            name ->
                name.equals("absent")
                    ? CompletableFuture.completedFuture(Optional.empty())
                    : CompletableFuture.failedFuture(new IllegalStateException("unavailable")),
            Duration.ofMinutes(1),
            Clock.systemUTC(),
            metrics);
    assertTrue(cache.resolve("absent").toCompletableFuture().join().isEmpty());
    assertTrue(cache.resolve("failure").toCompletableFuture().isCompletedExceptionally());
    assertEquals(2, metrics.misses.get());
    assertEquals(1, metrics.absent.get());
    assertEquals(1, metrics.failed.get());
  }

  @Test
  void failingObserverDoesNotChangeResolutionOrLastCallerCancellation() {
    SecretCacheObserver broken =
        new SecretCacheObserver() {
          @Override
          public void hit() {
            throw new IllegalStateException();
          }

          @Override
          public void miss() {
            throw new IllegalStateException();
          }

          @Override
          public void loaded(LoadOutcome outcome, Duration elapsed) {
            throw new IllegalStateException();
          }
        };
    var sync =
        new CachingSecretResolver(
            name -> Optional.of(SecretValue.of("value")),
            Duration.ofMinutes(1),
            Clock.systemUTC(),
            broken);
    assertEquals("value", sync.require("key").reveal());
    assertEquals("value", sync.require("key").reveal());
    var load = new CompletableFuture<Optional<SecretValue>>();
    var async =
        new CachingAsyncSecretResolver(
            name -> load, Duration.ofMinutes(1), Clock.systemUTC(), broken);
    var first = async.resolve("key").toCompletableFuture();
    var second = async.resolve("key").toCompletableFuture();
    first.cancel(true);
    assertFalse(load.isCancelled());
    second.cancel(true);
    assertTrue(load.isCancelled());
  }
}
