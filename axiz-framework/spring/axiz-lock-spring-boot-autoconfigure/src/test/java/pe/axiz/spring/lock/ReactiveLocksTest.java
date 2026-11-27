package pe.axiz.spring.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.AsyncLockHandle;
import pe.axiz.core.lock.LockLostException;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@ExtendWith(BlockHoundExtension.class)
class ReactiveLocksTest {
  static final class Store implements AsyncDistributedLock {
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger renewals = new AtomicInteger();
    final AtomicInteger releases = new AtomicInteger();
    boolean busy;
    boolean renew = true;
    final AsyncLockHandle handle =
        new AsyncLockHandle() {
          @Override
          public String resource() {
            return "account:1";
          }

          @Override
          public String token() {
            return "owner-1";
          }

          @Override
          public CompletionStage<Boolean> renew(Duration lease) {
            renewals.incrementAndGet();
            return CompletableFuture.completedFuture(renew);
          }

          @Override
          public CompletionStage<Void> release() {
            releases.incrementAndGet();
            return CompletableFuture.completedFuture(null);
          }
        };

    @Override
    public CompletionStage<Optional<AsyncLockHandle>> tryAcquire(String resource, Duration lease) {
      attempts.incrementAndGet();
      return CompletableFuture.completedFuture(busy ? Optional.empty() : Optional.of(handle));
    }
  }

  @Test
  void validatesTimingAndDoesNotHideLateErrorsOrReleaseBeforeWorkCompletes() {
    Store store = new Store();
    assertThrows(IllegalArgumentException.class, () -> new ReactiveLocks(store, Duration.ZERO));
    var locks = new ReactiveLocks(store, Duration.ofMillis(10));
    assertThrows(
        IllegalArgumentException.class,
        () -> locks.withLock("a", Duration.ofMillis(2), Duration.ZERO, Mono::empty));
    assertThrows(
        IllegalArgumentException.class,
        () -> locks.withLock("a", Duration.ofSeconds(1), Duration.ofMillis(-1), Mono::empty));
    assertEquals(
        null,
        locks
            .withLock("a", Duration.ofSeconds(1), Duration.ZERO, Mono::empty)
            .block(Duration.ofSeconds(2)));
    var completed = new AtomicBoolean();
    assertThrows(
        IllegalStateException.class,
        () ->
            locks
                .withLock(
                    "a",
                    Duration.ofSeconds(1),
                    Duration.ZERO,
                    () ->
                        Flux.concat(
                                Mono.just("first"), Mono.error(new IllegalStateException("late")))
                            .doOnError(failure -> completed.set(true)))
                .block(Duration.ofSeconds(2)));
    assertEquals(2, store.releases.get());
    assertTrue(completed.get());
  }

  @Test
  void renewsWithoutBlockingAndReleasesAfterWork() {
    Store store = new Store();
    var locks = new ReactiveLocks(store, Duration.ofMillis(10));
    String answer =
        locks
            .withLock(
                "account:1",
                Duration.ofSeconds(1),
                Duration.ZERO,
                held -> {
                  held.ensureHeld();
                  assertEquals("owner-1", held.token());
                  return Mono.delay(Duration.ofMillis(1200))
                      .map(
                          ignored -> {
                            held.ensureHeld();
                            return "paid";
                          });
                })
            .subscribeOn(Schedulers.parallel())
            .block(Duration.ofSeconds(5));
    assertEquals("paid", answer);
    assertTrue(store.renewals.get() >= 1);
    assertEquals(1, store.releases.get());
  }

  @Test
  void aLostLeaseCancelsWorkAndCannotReturnSuccess() {
    Store store = new Store();
    store.renew = false;
    AtomicBoolean cancelled = new AtomicBoolean();
    var locks = new ReactiveLocks(store, Duration.ofMillis(10));
    assertThrows(
        LockLostException.class,
        () ->
            locks
                .withLock(
                    "account:1",
                    Duration.ofMillis(150),
                    Duration.ZERO,
                    () -> Mono.never().doOnCancel(() -> cancelled.set(true)))
                .block(Duration.ofSeconds(2)));
    assertTrue(cancelled.get());
    assertEquals(1, store.releases.get());
  }

  @Test
  void cancellationAndBusinessFailureReleaseTheirOwner() {
    Store store = new Store();
    var locks = new ReactiveLocks(store, Duration.ofMillis(10));
    var running =
        locks.withLock("account:1", Duration.ofSeconds(1), Duration.ZERO, Mono::never).toFuture();
    running.cancel(true);
    assertEquals(1, store.releases.get());
    IllegalArgumentException failure = new IllegalArgumentException("business failure");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            locks
                .withLock(
                    "account:1", Duration.ofSeconds(1), Duration.ZERO, () -> Mono.error(failure))
                .block(Duration.ofSeconds(1)));
    assertEquals(2, store.releases.get());
  }

  @Test
  void busyAcquisitionUsesABoundedNonBlockingWait() {
    Store store = new Store();
    store.busy = true;
    var locks = new ReactiveLocks(store, Duration.ofMillis(10));
    assertThrows(
        BusinessException.class,
        () ->
            locks
                .withLock(
                    "account:1",
                    Duration.ofSeconds(1),
                    Duration.ofMillis(50),
                    () -> Mono.just("must not run"))
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(2)));
    assertTrue(store.attempts.get() > 1);
    assertEquals(0, store.releases.get());
  }

  @Test
  void anAnswerAfterLeaseExpiryIsReleasedWithoutRunningWork() throws Exception {
    Store store = new Store();
    var answer = new CompletableFuture<Optional<AsyncLockHandle>>();
    var locks = new ReactiveLocks((resource, lease) -> answer, Duration.ofMillis(10));
    AtomicBoolean ran = new AtomicBoolean();
    var running =
        locks
            .withLock(
                "account:1",
                Duration.ofMillis(20),
                Duration.ZERO,
                () -> Mono.fromRunnable(() -> ran.set(true)))
            .toFuture();
    // Se hace expirar el lease monotónico: el tiempo real transcurrido es el comportamiento bajo
    // prueba.
    Thread.sleep(40);
    answer.complete(Optional.of(store.handle));
    CompletionException failure = assertThrows(CompletionException.class, running::join);
    assertInstanceOf(BusinessException.class, failure.getCause());
    assertTrue(!ran.get());
    assertEquals(1, store.releases.get());
  }
}
