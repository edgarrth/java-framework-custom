package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SecretCacheOwnershipTest {
  @Test
  void coalescedCallersOwnIndependentCopiesThatSurviveEvictionAndCacheClose() {
    var pending = new CompletableFuture<Optional<SecretValue>>();
    var owned = SecretValue.of("shared");
    try (var cache =
        new CachingAsyncSecretResolver(name -> pending, Duration.ofMinutes(1), Clock.systemUTC())) {
      var first = cache.require("key").toCompletableFuture();
      var second = cache.require("key").toCompletableFuture();
      pending.complete(Optional.of(owned));
      try (var a = first.join();
          var b = second.join()) {
        assertNotSame(owned, a);
        assertNotSame(a, b);
        cache.evict("key");
        assertThrows(IllegalStateException.class, owned::reveal);
        assertEquals("shared", a.reveal());
        assertEquals("shared", b.reveal());
        a.close();
        assertEquals("shared", b.reveal());
        cache.close();
        assertEquals("shared", b.reveal());
        assertThrows(
            CompletionException.class, () -> cache.resolve("key").toCompletableFuture().join());
      }
    }
  }

  @Test
  void lruRetirementAndZeroTtlEraseCacheValuesButNotDeliveredSnapshots() {
    var originals = new ArrayList<SecretValue>();
    SecretResolver source =
        name -> {
          var value = SecretValue.of(name);
          originals.add(value);
          return Optional.of(value);
        };
    try (var cache = new CachingSecretResolver(source, Duration.ofMinutes(1), Clock.systemUTC());
        var delivered = cache.require("first")) {
      for (int i = 0; i < 1000; i++) {
        try (var ignored = cache.require("key-" + i)) {}
      }
      assertThrows(IllegalStateException.class, originals.getFirst()::reveal);
      assertEquals("first", delivered.reveal());
    }
    for (var value : originals) assertThrows(IllegalStateException.class, value::reveal);
    var transientValue = SecretValue.of("transient");
    try (var cache =
            new CachingSecretResolver(
                name -> Optional.of(transientValue), Duration.ZERO, Clock.systemUTC());
        var delivered = cache.require("transient")) {
      assertThrows(IllegalStateException.class, transientValue::reveal);
      assertEquals("transient", delivered.reveal());
    }
  }

  @Test
  void lateUncancellableLoadsAreErasedAfterLastCancellationOrCacheClose() {
    for (boolean close : List.of(false, true)) {
      var pending =
          new CompletableFuture<Optional<SecretValue>>() {
            @Override
            public boolean cancel(boolean interrupt) {
              return false;
            }
          };
      var value = SecretValue.of("late");
      try (var cache =
          new CachingAsyncSecretResolver(
              name -> pending, Duration.ofMinutes(1), Clock.systemUTC())) {
        var result = cache.require("key").toCompletableFuture();
        if (close) cache.close();
        else assertTrue(result.cancel(true));
        assertTrue(result.isCompletedExceptionally());
        assertFalse(pending.isDone());
        pending.complete(Optional.of(value));
        assertThrows(IllegalStateException.class, value::reveal);
      }
    }
  }

  @Test
  void concurrentReadersAndEvictionCannotInvalidateReturnedCopies() throws Exception {
    try (var cache =
            new CachingSecretResolver(
                name -> Optional.of(SecretValue.of("value")),
                Duration.ofMinutes(1),
                Clock.systemUTC());
        var workers = Executors.newFixedThreadPool(8)) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < 1000; i++) {
        tasks.add(
            workers.submit(
                () -> {
                  try (var value = cache.require("key")) {
                    cache.evict("key");
                    assertEquals("value", value.reveal());
                  }
                }));
      }
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void requiredResolutionErasesALateSecretFromAnUncancellableSource() {
    var source =
        new CompletableFuture<Optional<SecretValue>>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    AsyncSecretResolver resolver = name -> source;
    var required = resolver.require("key").toCompletableFuture();
    assertTrue(required.cancel(true));
    var late = SecretValue.of("late");
    source.complete(Optional.of(late));
    assertThrows(IllegalStateException.class, late::reveal);
  }
}
