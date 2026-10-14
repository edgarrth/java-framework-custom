package pe.axiz.spring.httpclient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.http.ExternalErrorTranslator;
import pe.axiz.core.http.HttpRetryRules;
import pe.axiz.core.resilience.Backoff;

/** Circuit breaker, bulkhead and retry decisions of one client. */
public final class ClientResilience {

  private final ClientSettings settings;
  private final @Nullable CircuitBreaker circuitBreaker;
  private final @Nullable Bulkhead bulkhead;
  private final Backoff backoff =
      Backoff.exponential(Duration.ofMillis(200), Duration.ofSeconds(2));

  public ClientResilience(ClientSettings settings, CircuitBreakerRegistry registry) {
    this.settings = settings;
    String cb = settings.circuitBreaker();
    this.circuitBreaker =
        cb == null
            ? null
            : registry.getConfiguration(cb).isPresent()
                ? registry.circuitBreaker("http." + settings.name(), cb)
                : registry.circuitBreaker("http." + settings.name());
    this.bulkhead =
        settings.maxConcurrentCalls() > 0
            ? Bulkhead.of(
                "http." + settings.name(),
                BulkheadConfig.custom()
                    .maxConcurrentCalls(settings.maxConcurrentCalls())
                    .maxWaitDuration(Duration.ZERO)
                    .build())
            : null;
  }

  /** Acquires bulkhead and circuit permissions or fails fast with a 503-category error. */
  public void acquire() {
    if (bulkhead != null && !bulkhead.tryAcquirePermission()) {
      throw unavailable("bulkhead-full");
    }
    if (circuitBreaker != null && !circuitBreaker.tryAcquirePermission()) {
      if (bulkhead != null) {
        bulkhead.onComplete();
      }
      throw unavailable("circuit-open");
    }
  }

  public void release(long startNanos, @Nullable Throwable failure, int status) {
    if (circuitBreaker != null) {
      long elapsed = System.nanoTime() - startNanos;
      if (failure != null) {
        circuitBreaker.onError(elapsed, TimeUnit.NANOSECONDS, failure);
      } else if (status >= 500 || status == 429) {
        circuitBreaker.onError(
            elapsed,
            TimeUnit.NANOSECONDS,
            ExternalErrorTranslator.translate(settings.name(), status, null, null));
      } else {
        circuitBreaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
      }
    }
    if (bulkhead != null) {
      bulkhead.onComplete();
    }
  }

  /**
   * Whether another attempt is allowed for this failure (or retryable status) within the budget.
   */
  public boolean retry(
      String method,
      @Nullable String idempotencyKey,
      @Nullable Throwable failure,
      int status,
      int attempt,
      long firstStartNanos) {
    if (failure instanceof ExternalServiceException e
        && e.external() != null
        && ("circuit-open".equals(e.external().code())
            || "bulkhead-full".equals(e.external().code()))) {
      return false;
    }
    Throwable reason =
        failure != null
            ? failure
            : ExternalErrorTranslator.translate(settings.name(), status, null, null);
    long spent = System.nanoTime() - firstStartNanos;
    long next = backoff.delay(attempt).toNanos();
    return HttpRetryRules.shouldRetry(settings, method, idempotencyKey, reason, attempt)
        && spent + next < settings.timeouts().total().toNanos();
  }

  public Duration delay(int attempt) {
    return backoff.delay(attempt);
  }

  public static boolean retryableStatus(int status) {
    return status == 429 || status == 502 || status == 503 || status == 504;
  }

  ExternalServiceException unavailable(String reason) {
    return ExternalServiceException.from(
        new ExternalErrorDetail(settings.name(), -1, reason, null, ""));
  }

  public ClientSettings settings() {
    return settings;
  }
}
