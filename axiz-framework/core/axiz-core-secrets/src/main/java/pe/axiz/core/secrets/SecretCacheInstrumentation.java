package pe.axiz.core.secrets;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import pe.axiz.core.commons.Stages;

/** Observes actual coalesced loads without wrapping or replacing their cancellation target. */
final class SecretCacheInstrumentation {
  private final SecretCacheObserver observer;
  private final AtomicBoolean reported = new AtomicBoolean();

  SecretCacheInstrumentation(SecretCacheObserver observer) {
    this.observer = Objects.requireNonNull(observer);
  }

  void lookup(boolean hit) {
    safely(hit ? observer::hit : observer::miss);
  }

  CompletionStage<Optional<SecretValue>> load(
      Supplier<CompletionStage<Optional<SecretValue>>> loader) {
    long start = System.nanoTime();
    var stage = Stages.call(loader);
    Stages.observe(
        stage,
        (value, failure) -> {
          var outcome =
              failure != null
                  ? SecretCacheObserver.LoadOutcome.FAILED
                  : value != null && value.isPresent()
                      ? SecretCacheObserver.LoadOutcome.FOUND
                      : SecretCacheObserver.LoadOutcome.ABSENT;
          safely(() -> observer.loaded(outcome, Duration.ofNanos(System.nanoTime() - start)));
        },
        failure -> reportFailure());
    return stage;
  }

  private void safely(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ignored) {
      reportFailure();
    }
  }

  private void reportFailure() {
    if (reported.compareAndSet(false, true))
      System.getLogger(SecretCacheInstrumentation.class.getName())
          .log(System.Logger.Level.DEBUG, "Secret cache observer failed; resolution is unaffected");
  }
}
