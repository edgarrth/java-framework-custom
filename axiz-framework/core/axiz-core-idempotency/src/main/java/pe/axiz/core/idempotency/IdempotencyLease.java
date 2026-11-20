package pe.axiz.core.idempotency;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import pe.axiz.core.concurrent.Stages;

/**
 * Owned renewal lifetime. Each attempt has a deadline, including submission by a synchronous
 * driver. Losing the lease marks the execution uncertain once; it never releases the reservation.
 */
public final class IdempotencyLease implements AutoCloseable {
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final Thread worker;

  private IdempotencyLease(
      Duration interval,
      Duration renewalTimeout,
      Supplier<CompletionStage<Boolean>> renew,
      Supplier<CompletionStage<Boolean>> uncertain) {
    Objects.requireNonNull(interval);
    Objects.requireNonNull(renewalTimeout);
    Objects.requireNonNull(renew);
    Objects.requireNonNull(uncertain);
    if (interval.compareTo(Duration.ofMillis(1)) < 0
        || renewalTimeout.isNegative()
        || renewalTimeout.isZero())
      throw new IllegalArgumentException("Invalid lease renewal timing");
    Duration period = interval;
    long timeoutNanos = renewalTimeout.toNanos();
    worker =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  try {
                    while (active.get()) {
                      Thread.sleep(period);
                      if (!active.get()) return;
                      if (!renewWithin(renew, timeoutNanos)) {
                        lose(uncertain, timeoutNanos);
                        return;
                      }
                    }
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });
    worker.start();
  }

  @SuppressWarnings("FutureReturnValueIgnored") // Completion is explicitly forwarded to answer.
  private static boolean renewWithin(Supplier<CompletionStage<Boolean>> renew, long timeoutNanos)
      throws InterruptedException {
    var answer = new CompletableFuture<Boolean>();
    var source = new AtomicReference<CompletableFuture<?>>();
    Thread submission =
        Thread.ofVirtual()
            .start(
                () -> {
                  var stage = Stages.call(renew);
                  source.set(stage.toCompletableFuture());
                  if (answer.isDone()) stage.toCompletableFuture().cancel(true);
                  stage.whenComplete(
                      (held, failure) -> {
                        if (failure == null) answer.complete(held);
                        else answer.completeExceptionally(failure);
                      });
                });
    try {
      return Boolean.TRUE.equals(answer.get(timeoutNanos, TimeUnit.NANOSECONDS));
    } catch (ExecutionException | TimeoutException failure) {
      return false;
    } finally {
      answer.cancel(true);
      submission.interrupt();
      var pending = source.get();
      if (pending != null) pending.cancel(true);
    }
  }

  private void lose(Supplier<CompletionStage<Boolean>> uncertain, long timeoutNanos) {
    if (!active.compareAndSet(true, false)) return;
    try {
      if (!renewWithin(uncertain, timeoutNanos)) reportFailure();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      reportFailure();
    }
  }

  private static void reportFailure() {
    // Neither provider messages nor causes/suppressed exceptions are safe to log.
    System.getLogger(IdempotencyLease.class.getName())
        .log(
            System.Logger.Level.ERROR,
            "Idempotency reservation lost; unable to persist UNKNOWN, retained reservation requires reconciliation");
  }

  /** Uses one renewal interval as the I/O deadline (services renew at one third of their lease). */
  public static IdempotencyLease start(
      Duration interval,
      Supplier<CompletionStage<Boolean>> renew,
      Supplier<CompletionStage<Boolean>> uncertain) {
    return start(interval, interval, renew, uncertain);
  }

  public static IdempotencyLease start(
      Duration interval,
      Duration renewalTimeout,
      Supplier<CompletionStage<Boolean>> renew,
      Supplier<CompletionStage<Boolean>> uncertain) {
    return new IdempotencyLease(interval, renewalTimeout, renew, uncertain);
  }

  @Override
  public void close() {
    active.set(false);
    worker.interrupt();
  }
}
