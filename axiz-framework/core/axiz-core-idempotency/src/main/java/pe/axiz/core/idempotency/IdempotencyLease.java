package pe.axiz.core.idempotency;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import pe.axiz.core.commons.Stages;

/** Owned renewal lifetime. Async I/O is composed; only the virtual timer sleeps. */
public final class IdempotencyLease implements AutoCloseable {
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final AtomicBoolean inFlight = new AtomicBoolean();
  private final Thread worker;

  private IdempotencyLease(
      Duration interval,
      Supplier<CompletionStage<Boolean>> renew,
      Supplier<CompletionStage<Boolean>> uncertain) {
    Duration period =
        interval.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : interval;
    worker =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  try {
                    while (active.get()) {
                      Thread.sleep(period);
                      if (!active.get() || !inFlight.compareAndSet(false, true)) continue;
                      try {
                        renew
                            .get()
                            .whenComplete(
                                (held, failure) -> {
                                  inFlight.set(false);
                                  if ((failure != null || !Boolean.TRUE.equals(held))
                                      && active.get()) {
                                    lose(uncertain);
                                  }
                                });
                      } catch (RuntimeException failure) {
                        lose(uncertain);
                      }
                    }
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });
    worker.start();
  }

  private void lose(Supplier<CompletionStage<Boolean>> uncertain) {
    if (!active.compareAndSet(true, false)) return;
    Stages.observe(
        Stages.call(uncertain),
        (changed, failure) -> {
          if (failure != null) reportFailure(failure);
        },
        IdempotencyLease::reportFailure);
    worker.interrupt();
  }

  private static void reportFailure(Throwable failure) {
    System.getLogger(IdempotencyLease.class.getName())
        .log(
            System.Logger.Level.ERROR,
            "Idempotency reservation lost; unable to persist UNKNOWN, retained reservation requires"
                + " reconciliation",
            failure);
  }

  public static IdempotencyLease start(
      Duration interval,
      Supplier<CompletionStage<Boolean>> renew,
      Supplier<CompletionStage<Boolean>> uncertain) {
    return new IdempotencyLease(interval, renew, uncertain);
  }

  @Override
  public void close() {
    active.set(false);
    worker.interrupt();
  }
}
