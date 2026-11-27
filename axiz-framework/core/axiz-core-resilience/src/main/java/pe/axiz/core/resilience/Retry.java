package pe.axiz.core.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Ejecuta una operación con una política de reintentos. Se usa donde no está disponible
 * {@code @Retryable} de Spring (capa 1, lectores batch). Las esperas usan el sleeper inyectado para
 * que las pruebas no esperen.
 */
public final class Retry {

  /** Pausa entre intentos. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    static Sleeper threadSleep() {
      return d -> Thread.sleep(d.toMillis());
    }
  }

  /** Observador de reintentos (métricas, logs). */
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
   * Ejecuta la operación, reintentando según lo permita la política.
   *
   * @param operation la operación
   * @return su resultado
   * @throws Exception el último fallo cuando se agotan los intentos o no es reintentable
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
