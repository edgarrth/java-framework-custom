package pe.axiz.spring.lock;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.AsyncLockHandle;
import pe.axiz.core.lock.LockErrors;
import pe.axiz.core.lock.LockLease;
import pe.axiz.core.lock.LockLostException;
import pe.axiz.core.lock.LockTiming;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Non-blocking acquisition, renewal and release; lease loss cancels work and is never reported as
 * success.
 */
public final class ReactiveLocks {
  private final AsyncDistributedLock locks;
  private final Duration poll;

  public ReactiveLocks(AsyncDistributedLock locks, Duration poll) {
    this.locks = Objects.requireNonNull(locks);
    this.poll = Objects.requireNonNull(poll);
    if (!poll.isPositive()) throw new IllegalArgumentException("poll interval must be positive");
  }

  public <T> Mono<T> withLock(
      String resource, Duration lease, Duration wait, Supplier<? extends Publisher<T>> work) {
    return withLock(resource, lease, wait, held -> work.get());
  }

  public <T> Mono<T> withLock(
      String resource,
      Duration lease,
      Duration wait,
      Function<LockLease, ? extends Publisher<T>> work) {
    if (lease.toMillis() <= 2 || wait.isNegative())
      throw new IllegalArgumentException(
          "lease must exceed drift margin and wait must not be negative");
    return Mono.defer(
        () ->
            Mono.usingWhen(
                acquire(resource, lease, System.nanoTime() + wait.toNanos()),
                held -> run(held, lease, work),
                held -> release(held),
                (held, error) -> release(held).onErrorComplete(),
                held -> release(held).onErrorComplete()));
  }

  private Mono<Held> acquire(String resource, Duration lease, long deadline) {
    return Mono.defer(
        () -> {
          long sent = System.nanoTime();
          return ReactiveStages.from(() -> locks.tryAcquire(resource, lease))
              .flatMap(
                  answer -> {
                    if (answer.isPresent()) {
                      Held held = new Held(answer.get(), LockTiming.validUntil(sent, lease));
                      if (held.held()) return Mono.just(held);
                      return release(held).onErrorComplete().then(retry(resource, lease, deadline));
                    }
                    return retry(resource, lease, deadline);
                  });
        });
  }

  private Mono<Held> retry(String resource, Duration lease, long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0)
      return Mono.error(
          new BusinessException(
              LockErrors.BUSY, "lock not acquired", Map.of("resource", resource)));
    return Mono.delay(Duration.ofNanos(Math.min(poll.toNanos(), remaining)))
        .then(acquire(resource, lease, deadline));
  }

  private <T> Mono<T> run(
      Held held, Duration lease, Function<LockLease, ? extends Publisher<T>> work) {
    Mono<T> guarded =
        Mono.defer(
                () -> {
                  held.ensureHeld();
                  return Flux.from(work.apply(held)).singleOrEmpty();
                })
            .flatMap(
                value -> {
                  held.ensureHeld();
                  return Mono.just(value);
                })
            .switchIfEmpty(
                Mono.defer(
                    () -> {
                      held.ensureHeld();
                      return Mono.empty();
                    }));
    Mono<T> renewals =
        Flux.interval(Duration.ofMillis(Math.max(1, lease.toMillis() / 3)))
            .onBackpressureDrop()
            .concatMap(tick -> renew(held, lease), 1)
            .then(Mono.never());
    return Mono.firstWithSignal(guarded, renewals);
  }

  private Mono<Void> renew(Held held, Duration lease) {
    return Mono.defer(
        () -> {
          long sent = System.nanoTime();
          long remaining = held.until.get() - sent;
          if (remaining <= 0) {
            held.lost.set(true);
            return Mono.error(new LockLostException(held.resource(), null));
          }
          return ReactiveStages.from(() -> held.handle.renew(lease))
              .timeout(Duration.ofNanos(remaining))
              .flatMap(
                  renewed -> {
                    if (!renewed || !held.held()) {
                      held.lost.set(true);
                      return Mono.error(new LockLostException(held.resource(), null));
                    }
                    held.until.set(LockTiming.validUntil(sent, lease));
                    held.ensureHeld();
                    return Mono.<Void>empty();
                  })
              .onErrorResume(
                  failure -> {
                    if (failure instanceof LockLostException || !held.held()) {
                      held.lost.set(true);
                      return Mono.error(
                          failure instanceof LockLostException
                              ? failure
                              : new LockLostException(held.resource(), failure));
                    }
                    return Mono.empty();
                  });
        });
  }

  private static Mono<Void> release(Held held) {
    held.lost.set(true);
    return ReactiveStages.from(held.handle::release).timeout(Duration.ofSeconds(5));
  }

  private static final class Held implements LockLease {
    final AsyncLockHandle handle;
    final AtomicLong until;
    final AtomicBoolean lost = new AtomicBoolean();

    Held(AsyncLockHandle handle, long until) {
      this.handle = handle;
      this.until = new AtomicLong(until);
    }

    @Override
    public String resource() {
      return handle.resource();
    }

    @Override
    public String token() {
      return handle.token();
    }

    @Override
    public boolean held() {
      return !lost.get() && System.nanoTime() - until.get() < 0;
    }

    @Override
    public void ensureHeld() {
      if (!held()) throw new LockLostException(resource(), null);
    }
  }
}
