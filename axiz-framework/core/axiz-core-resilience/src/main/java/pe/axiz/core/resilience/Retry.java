package pe.axiz.core.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Executes an operation with a retry policy. Used where Spring's {@code @Retryable} is not
 * available (layer 1, batch readers). Sleeping uses the injected sleeper so tests do not wait.
 */
public final class Retry {

  /** Pauses between attempts. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    static Sleeper threadSleep() {
      return d -> Thread.sleep(d.toMillis());
    }
  }

  /** Observer of retries (metrics, logs). */
  @FunctionalInterface
  public interface Listener {
    void onRetry(int attempt, Throwable failure, Duration delay);

    static Listener none() {
      return (a, f, d) -> {};
    }
  }

  private final RetryPolicy policy;
  private final Sleeper sleeper;
  private final Listener listener;

  public Retry(RetryPolicy policy, Sleeper sleeper, Listener listener) {
    this.policy = Objects.requireNonNull(policy);
    this.sleeper = Objects.requireNonNull(sleeper);
    this.listener = Objects.requireNonNull(listener);
  }

  public Retry(RetryPolicy policy) {
    this(policy, Sleeper.threadSleep(), Listener.none());
  }

  /**
   * Runs the operation, retrying as the policy allows.
   *
   * @param operation the operation
   * @return its result
   * @throws Exception the last failure when attempts are exhausted or it is not retryable
   */
  public <T> T call(Callable<T> operation) throws Exception {
    int attempt = 1;
    while (true) {
      try {
        return operation.call();
      } catch (Exception failure) {
        if (attempt >= policy.maxAttempts() || !policy.retryOn().test(failure)) {
          throw failure;
        }
        Duration delay = policy.backoff().delay(attempt);
        listener.onRetry(attempt, failure, delay);
        try {
          sleeper.sleep(delay);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          failure.addSuppressed(ie);
          throw failure;
        }
        attempt++;
      }
    }
  }
}
