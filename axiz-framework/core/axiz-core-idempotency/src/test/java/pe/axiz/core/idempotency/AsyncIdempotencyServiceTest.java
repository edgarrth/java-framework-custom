package pe.axiz.core.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class AsyncIdempotencyServiceTest {
  static final String KEY = "a-valid-idempotency-key-00001";
  static final Duration LEASE = Duration.ofSeconds(5);
  static final StoredResponse RESPONSE =
      new StoredResponse(201, Map.of("Location", "/orders/1"), new byte[] {1});

  static AsyncIdempotencyService service(AsyncIdempotencyStore store, Clock clock) {
    return new AsyncIdempotencyService(store, LEASE, Duration.ofHours(24), clock);
  }

  @Test
  void replayConflictAndTenantIsolationHaveTheSameContractAsImperativeApi() {
    var clock = new IdempotencyTest.TestClock();
    var service = service(new InMemoryAsyncIdempotencyStore(clock), clock);
    var first =
        assertInstanceOf(
            IdempotencyService.Proceed.class,
            service.begin("tenant-a", KEY, "fp").toCompletableFuture().join());
    assertEquals(409, failure(service.begin("tenant-a", KEY, "fp")).category().httpStatus());
    assertEquals(422, failure(service.begin("tenant-a", KEY, "different")).category().httpStatus());
    assertInstanceOf(
        IdempotencyService.Proceed.class,
        service.begin("tenant-b", KEY, "fp").toCompletableFuture().join());
    assertTrue(service.complete(first, RESPONSE).toCompletableFuture().join());
    var replay =
        assertInstanceOf(
            IdempotencyService.Replay.class,
            service.begin("tenant-a", KEY, "fp").toCompletableFuture().join());
    assertEquals(201, replay.response().status());
    assertEquals("/orders/1", replay.response().headers().get("Location"));
    assertEquals(422, failure(service.begin("tenant-a", KEY, "different")).category().httpStatus());
  }

  @Test
  void staleOwnerCannotOverwriteOrReleaseANewExecutionAndFailuresAreRetryable() {
    var clock = new IdempotencyTest.TestClock();
    var service = service(new InMemoryAsyncIdempotencyStore(clock), clock);
    var old =
        (IdempotencyService.Proceed)
            service.begin("tenant", KEY, "fp").toCompletableFuture().join();
    clock.now = clock.now.plus(Duration.ofDays(2));
    var current =
        (IdempotencyService.Proceed)
            service.begin("tenant", KEY, "fp").toCompletableFuture().join();
    assertNotEquals(old.owner(), current.owner());
    assertFalse(service.complete(old, RESPONSE).toCompletableFuture().join());
    assertFalse(service.fail(old).toCompletableFuture().join());
    assertTrue(service.releaseWithoutEffect(current).toCompletableFuture().join());
    var retry =
        (IdempotencyService.Proceed)
            service.begin("tenant", KEY, "fp").toCompletableFuture().join();
    assertTrue(service.complete(retry, RESPONSE).toCompletableFuture().join());
    clock.now = clock.now.plus(Duration.ofDays(2));
    assertInstanceOf(
        IdempotencyService.Proceed.class,
        service.begin("tenant", KEY, "fp").toCompletableFuture().join());
  }

  static class RaceStore implements AsyncIdempotencyStore {
    @Override
    public CompletionStage<Boolean> compareAndSet(
        IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
      return CompletableFuture.completedFuture(false);
    }

    final AtomicInteger attempts = new AtomicInteger();
    boolean acquireRetry = true;
    CompletableFuture<Boolean> first = CompletableFuture.completedFuture(false);

    @Override
    public CompletionStage<Boolean> tryStart(IdempotencyRecord record) {
      return attempts.incrementAndGet() == 1
          ? first
          : CompletableFuture.completedFuture(acquireRetry);
    }

    @Override
    public CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant now) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletionStage<Boolean> complete(
        String key, String owner, StoredResponse response, Instant expiry) {
      return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletionStage<Boolean> release(String key, String owner) {
      return CompletableFuture.completedFuture(false);
    }
  }

  @Test
  void expirationBetweenAcquireAndLookupNeverAutomaticallyReexecutes() {
    var clock = new IdempotencyTest.TestClock();
    var race = new RaceStore();
    assertEquals(409, failure(service(race, clock).begin("s", KEY, "fp")).category().httpStatus());
    assertEquals(1, race.attempts.get());
  }

  @Test
  void cancellationAndStoreFailureRemainVisible() {
    var clock = new IdempotencyTest.TestClock();
    var race = new RaceStore();
    race.first = new CompletableFuture<>();
    var pending = service(race, clock).begin("s", KEY, "fp").toCompletableFuture();
    pending.cancel(true);
    assertTrue(race.first.isCancelled());
    race = new RaceStore();
    var expected = new IllegalStateException("backend unavailable");
    race.first = CompletableFuture.failedFuture(expected);
    CompletionStage<?> failed = service(race, clock).begin("s", KEY, "fp");
    assertEquals(
        expected,
        assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join())
            .getCause());
  }

  @Test
  void invalidLeaseAndMissingKeysAreRejectedBeforeAcquiring() {
    var clock = new IdempotencyTest.TestClock();
    var store = new RaceStore();
    assertThrows(
        IllegalArgumentException.class,
        () -> new AsyncIdempotencyService(store, Duration.ZERO, LEASE, clock));
    var service = service(store, clock);
    assertEquals(
        400,
        assertThrows(BusinessException.class, () -> service.begin("s", null, "fp"))
            .category()
            .httpStatus());
    assertEquals(
        400,
        assertThrows(BusinessException.class, () -> service.begin("s", "short", "fp"))
            .category()
            .httpStatus());
    assertEquals(0, store.attempts.get());
  }

  static BusinessException failure(CompletionStage<?> operation) {
    return assertInstanceOf(
        BusinessException.class,
        assertThrows(CompletionException.class, () -> operation.toCompletableFuture().join())
            .getCause());
  }
}
