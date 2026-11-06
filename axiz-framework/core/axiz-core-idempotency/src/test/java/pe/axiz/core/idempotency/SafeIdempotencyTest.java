package pe.axiz.core.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class SafeIdempotencyTest {
  private final IdempotencyTest.TestClock clock = new IdempotencyTest.TestClock();
  private final IdempotencyService service =
      new IdempotencyService(
          new InMemoryIdempotencyStore(clock), Duration.ofSeconds(30), Duration.ofDays(1), clock);
  private static final String KEY = "request-123456789";

  @Test
  void anUncertainFailureDoesNotAuthorizeAnotherExecution() {
    var first =
        assertInstanceOf(IdempotencyService.Proceed.class, service.begin("payment", KEY, "fp"));
    service.fail(first);
    var failure = assertThrows(BusinessException.class, () -> service.begin("payment", KEY, "fp"));
    assertEquals(409, failure.category().httpStatus());
  }

  @Test
  void anExpiredExecutionLeaseIsNotAnExpiredOperation() {
    service.begin("payment", KEY, "fp");
    clock.now = clock.now.plusSeconds(31);
    var failure = assertThrows(BusinessException.class, () -> service.begin("payment", KEY, "fp"));
    assertEquals(409, failure.category().httpStatus());
  }

  @Test
  void renewalExtendsTheLeaseAndRetentionWithoutAllowingTakeover() {
    InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
    IdempotencyService svc =
        new IdempotencyService(store, Duration.ofSeconds(30), Duration.ofHours(1), clock);
    var owner = (IdempotencyService.Proceed) svc.begin("payment", KEY, "fp");
    clock.now = clock.now.plusSeconds(20);
    assertTrue(svc.renew(owner));
    var record = store.find(owner.key(), clock.instant()).orElseThrow();
    assertEquals(clock.instant().plusSeconds(30), record.leaseUntil());
    assertEquals(record.leaseUntil().plusSeconds(3600), record.retainUntil());
    clock.now = clock.now.plusSeconds(20);
    assertEquals(
        IdempotencyErrors.IN_PROGRESS.code(),
        assertThrows(BusinessException.class, () -> svc.begin("payment", KEY, "fp")).code());
    clock.now = clock.now.plusSeconds(31);
    assertFalse(svc.renew(owner));
    assertEquals(
        IdempotencyErrors.OUTCOME_UNKNOWN.code(),
        assertThrows(BusinessException.class, () -> svc.begin("payment", KEY, "fp")).code());
  }

  @Test
  void reconciliationIsExplicitAndNeverReexecutesTheCurrentRequest() {
    InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
    var first = new IdempotencyService(store, Duration.ofSeconds(30), Duration.ofHours(1), clock);
    var owner = (IdempotencyService.Proceed) first.begin("payment", KEY, "fp");
    first.fail(owner);
    AtomicInteger resolves = new AtomicInteger();
    var reconciled =
        new IdempotencyService(
            store,
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            clock,
            (key, record) -> {
              resolves.incrementAndGet();
              return Optional.of(
                  Resolution.completed(new StoredResponse(201, Map.of(), new byte[] {1})));
            });
    assertEquals(
        201,
        assertInstanceOf(IdempotencyService.Replay.class, reconciled.begin("payment", KEY, "fp"))
            .response()
            .status());
    assertInstanceOf(IdempotencyService.Replay.class, reconciled.begin("payment", KEY, "fp"));
    assertEquals(1, resolves.get());
    assertFalse(first.fail(owner));
    assertFalse(first.releaseWithoutEffect(owner));
  }

  @Test
  void noEffectResolutionReleasesOnlyAfterAuthoritativeReconciliation() {
    InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
    var svc =
        new IdempotencyService(
            store,
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            clock,
            (key, record) -> Optional.of(Resolution.withoutEffect()));
    var owner = (IdempotencyService.Proceed) svc.begin("payment", KEY, "fp");
    svc.fail(owner);
    assertEquals(
        IdempotencyErrors.RESOLVED_WITHOUT_EFFECT.code(),
        assertThrows(BusinessException.class, () -> svc.begin("payment", KEY, "fp")).code());
    assertInstanceOf(IdempotencyService.Proceed.class, svc.begin("payment", KEY, "fp"));
    assertFalse(svc.complete(owner, new StoredResponse(201, Map.of(), new byte[0])));
  }

  @Test
  void asynchronousReconciliationWaitsForItsNativeResult() {
    var store = new InMemoryAsyncIdempotencyStore(clock);
    CompletableFuture<Optional<Resolution>> answer = new CompletableFuture<>();
    var svc =
        new AsyncIdempotencyService(
            store, Duration.ofSeconds(30), Duration.ofHours(1), clock, (key, record) -> answer);
    var owner =
        (IdempotencyService.Proceed) svc.begin("payment", KEY, "fp").toCompletableFuture().join();
    svc.fail(owner).toCompletableFuture().join();
    var pending = svc.begin("payment", KEY, "fp").toCompletableFuture();
    assertFalse(pending.isDone());
    answer.complete(
        Optional.of(Resolution.completed(new StoredResponse(202, Map.of(), new byte[0]))));
    assertEquals(
        202, assertInstanceOf(IdempotencyService.Replay.class, pending.join()).response().status());
  }

  @Test
  void renewalFailureMarksUncertaintyAndStopsTheOwnedTimer() throws Exception {
    CountDownLatch uncertain = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    try (var lease =
        IdempotencyLease.start(
            Duration.ofMillis(1),
            () -> {
              attempts.incrementAndGet();
              return CompletableFuture.completedFuture(false);
            },
            () -> {
              uncertain.countDown();
              return CompletableFuture.completedFuture(true);
            })) {
      assertTrue(uncertain.await(5, TimeUnit.SECONDS));
    }
    assertEquals(1, attempts.get());
  }

  @Test
  void terminalAndLiveRecordsCannotBeResolvedOrMutatedByAnotherOwner() {
    var store = new InMemoryAsyncIdempotencyStore(clock);
    var now = clock.instant();
    var record =
        new IdempotencyRecord(
            "scoped",
            "fp",
            IdempotencyRecord.Status.IN_PROGRESS,
            "owner",
            null,
            now.plusSeconds(30),
            now.plusSeconds(3600),
            0,
            null);
    assertTrue(store.tryStart(record).toCompletableFuture().join());
    var response = new StoredResponse(201, Map.of("Location", "/payments/p1"), new byte[] {1, 2});
    assertFalse(
        store
            .resolve(record, Resolution.completed(response), now, now.plusSeconds(3600))
            .toCompletableFuture()
            .join());
    assertFalse(
        store
            .renew("scoped", "stranger", now.plusSeconds(40), now.plusSeconds(3640), now)
            .toCompletableFuture()
            .join());
    assertFalse(store.markUnknown("missing", "owner", "lost", now).toCompletableFuture().join());
    assertFalse(
        store
            .renew(
                "scoped", "owner", now.plusSeconds(100), now.plusSeconds(3700), now.plusSeconds(31))
            .toCompletableFuture()
            .join());
    assertTrue(
        store
            .renew("scoped", "owner", now.plusSeconds(40), now.plusSeconds(3640), now)
            .toCompletableFuture()
            .join());
    assertTrue(store.markUnknown("scoped", "owner", "unknown", now).toCompletableFuture().join());
    var unknown = store.find("scoped", now).toCompletableFuture().join().orElseThrow();
    assertFalse(store.releaseWithoutEffect("scoped", "owner").toCompletableFuture().join());
    assertFalse(
        store
            .resolve(record, Resolution.withoutEffect(), now.plusSeconds(31), now.plusSeconds(4000))
            .toCompletableFuture()
            .join(),
        "stale resolution version");
    assertTrue(
        store
            .resolve(unknown, Resolution.completed(response), now, now.plusSeconds(4000))
            .toCompletableFuture()
            .join());
    var completed = store.find("scoped", now).toCompletableFuture().join().orElseThrow();
    assertFalse(
        store
            .resolve(completed, Resolution.withoutEffect(), now, now.plusSeconds(4000))
            .toCompletableFuture()
            .join());
    assertFalse(store.markUnknown("scoped", "owner", "late", now).toCompletableFuture().join());
    assertFalse(
        store
            .resolve(
                unknown, Resolution.withoutEffect(), now.plusSeconds(5000), now.plusSeconds(6000))
            .toCompletableFuture()
            .join());
  }

  @Test
  void asynchronousNoEffectAndInconclusiveResolversNeverExecuteTheRetryAutomatically() {
    var store = new InMemoryAsyncIdempotencyStore(clock);
    var svc =
        new AsyncIdempotencyService(
            store,
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            clock,
            (key, record) -> CompletableFuture.completedFuture(Optional.empty()));
    var execution =
        (IdempotencyService.Proceed) svc.begin("payment", KEY, "fp").toCompletableFuture().join();
    assertTrue(svc.renew(execution).toCompletableFuture().join());
    assertTrue(svc.fail(execution).toCompletableFuture().join());
    var unknown =
        assertThrows(
            java.util.concurrent.CompletionException.class,
            () -> svc.begin("payment", KEY, "fp").toCompletableFuture().join());
    assertEquals(
        IdempotencyErrors.OUTCOME_UNKNOWN.code(), ((BusinessException) unknown.getCause()).code());
    var reconciler =
        new AsyncIdempotencyService(
            store,
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            clock,
            (key, record) ->
                CompletableFuture.completedFuture(Optional.of(Resolution.withoutEffect())));
    var resolved =
        assertThrows(
            java.util.concurrent.CompletionException.class,
            () -> reconciler.begin("payment", KEY, "fp").toCompletableFuture().join());
    assertEquals(
        IdempotencyErrors.RESOLVED_WITHOUT_EFFECT.code(),
        ((BusinessException) resolved.getCause()).code());
    var next =
        (IdempotencyService.Proceed)
            reconciler.begin("payment", KEY, "fp").toCompletableFuture().join();
    assertTrue(reconciler.releaseWithoutEffect(next).toCompletableFuture().join());
    assertFalse(
        svc.complete(execution, new StoredResponse(201, Map.of(), new byte[0]))
            .toCompletableFuture()
            .join());
  }

  @Test
  void nativeRenewalCompletingAfterRequestClosureCannotInvalidateTheResult() throws Exception {
    var pending = new CompletableFuture<Boolean>();
    var started = new CountDownLatch(1);
    var unknown = new AtomicInteger();
    var lease =
        IdempotencyLease.start(
            Duration.ZERO,
            () -> {
              started.countDown();
              return pending;
            },
            () -> {
              unknown.incrementAndGet();
              return CompletableFuture.completedFuture(true);
            });
    assertTrue(started.await(5, TimeUnit.SECONDS));
    lease.close();
    pending.complete(false);
    assertEquals(0, unknown.get());
  }

  @Test
  void synchronousSubmissionFailuresAlsoRetainAnUncertainOutcome() throws Exception {
    var retained = new CountDownLatch(1);
    try (var lease =
        IdempotencyLease.start(
            Duration.ofMillis(1),
            () -> {
              throw new IllegalStateException("transport submission failed");
            },
            () -> {
              retained.countDown();
              return CompletableFuture.completedFuture(true);
            })) {
      assertTrue(retained.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void replayBytesAndHeadersAreImmutableAndDifferentResponsesAreNeverEqual() {
    byte[] input = {1, 2};
    var headers = new java.util.HashMap<>(Map.of("Location", "/payments/p1"));
    var response = new StoredResponse(201, headers, input);
    input[0] = 9;
    headers.put("Location", "/payments/other");
    byte[] leaked = response.body();
    leaked[0] = 7;
    var same = new StoredResponse(201, Map.of("Location", "/payments/p1"), new byte[] {1, 2});
    assertEquals(same, response);
    assertEquals(same.hashCode(), response.hashCode());
    assertFalse(response.equals("other"));
    assertFalse(response.equals(new StoredResponse(202, same.headers(), same.body())));
    assertFalse(response.equals(new StoredResponse(201, Map.of(), same.body())));
    assertFalse(response.equals(new StoredResponse(201, same.headers(), new byte[] {2, 1})));
    assertFalse(response.bodyOmitted());
    assertTrue(response.toString().contains("2 bytes"));
  }
}
