package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsyncSecretsTest {
  @Test
  void orderedSourcesStopAtTheFirstValueAndOnlyAbsenceFallsThrough() {
    var calls = new AtomicInteger();
    AsyncSecretResolver remote =
        name -> {
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(Optional.of(SecretValue.of("remote")));
        };
    var environment = new EnvironmentAsyncSecretResolver(Map.of("PAYMENTS_DB_PASSWORD", "local"));
    var chain = new CompositeAsyncSecretResolver(List.of(environment, remote));
    assertEquals(
        "local", chain.require("payments/db-password").toCompletableFuture().join().reveal());
    assertEquals(0, calls.get());
    assertEquals("remote", chain.require("another").toCompletableFuture().join().reveal());
    assertEquals(1, calls.get());
    var outage = new IllegalStateException("offline");
    var broken =
        new CompositeAsyncSecretResolver(
            List.of(name -> CompletableFuture.failedFuture(outage), remote));
    assertSame(
        outage,
        assertThrows(
                CompletionException.class, () -> broken.require("key").toCompletableFuture().join())
            .getCause());
    assertEquals(1, calls.get(), "outages must not silently change the secret source");
    var missing =
        new CompositeAsyncSecretResolver(List.of()).require("missing").toCompletableFuture();
    assertInstanceOf(
        SecretNotFoundException.class,
        assertThrows(CompletionException.class, missing::join).getCause());
  }

  @Test
  void cancellingTheCompositeCancelsItsActiveNativeSourceWithoutQueryingTheNextOne() {
    var pending = new CompletableFuture<Optional<SecretValue>>();
    var later = new AtomicInteger();
    var chain =
        new CompositeAsyncSecretResolver(
            List.of(
                name -> pending,
                name -> {
                  later.incrementAndGet();
                  return CompletableFuture.completedFuture(Optional.empty());
                }));
    assertTrue(chain.require("key").toCompletableFuture().cancel(true));
    assertTrue(pending.isCancelled());
    assertEquals(0, later.get());
  }

  @Test
  void cachedLoadsCoalesceAndLastCancellationReachesTheDelegate() {
    var pending = new CompletableFuture<Optional<SecretValue>>();
    var calls = new AtomicInteger();
    var cache =
        new CachingAsyncSecretResolver(
            name -> {
              calls.incrementAndGet();
              return pending;
            },
            Duration.ofMinutes(1),
            Clock.systemUTC());
    var first = cache.resolve("key").toCompletableFuture();
    var second = cache.require("key").toCompletableFuture();
    assertEquals(1, calls.get());
    first.cancel(true);
    assertFalse(pending.isDone());
    second.cancel(true);
    assertTrue(pending.isCancelled());
  }

  @Test
  void missingValuesAreNotCachedAndEvictionAllowsRotation() {
    var value = new AtomicReference<Optional<SecretValue>>(Optional.empty());
    var cache =
        new CachingAsyncSecretResolver(
            name -> CompletableFuture.completedFuture(value.get()),
            Duration.ofMinutes(1),
            Clock.systemUTC());
    assertTrue(cache.resolve("key").toCompletableFuture().join().isEmpty());
    value.set(Optional.of(SecretValue.of("first")));
    assertEquals("first", cache.require("key").toCompletableFuture().join().reveal());
    value.set(Optional.of(SecretValue.of("rotated")));
    assertEquals("first", cache.require("key").toCompletableFuture().join().reveal());
    cache.evict("key");
    assertEquals("rotated", cache.require("key").toCompletableFuture().join().reveal());
  }

  @Test
  void synchronousCacheKeepsOriginalRuntimeAndFatalFailures() {
    var failure = new IllegalStateException("offline");
    var cache =
        new CachingSecretResolver(
            name -> {
              throw failure;
            },
            Duration.ofMinutes(1),
            Clock.systemUTC());
    assertSame(failure, assertThrows(IllegalStateException.class, () -> cache.resolve("key")));
    var fatal = new LinkageError("driver failed");
    var broken =
        new CachingSecretResolver(
            name -> {
              throw fatal;
            },
            Duration.ofMinutes(1),
            Clock.systemUTC());
    assertSame(fatal, assertThrows(LinkageError.class, () -> broken.resolve("key")));
  }
}
