package pe.axiz.core.messaging;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Predicate;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.resilience.Backoff;

/**
 * Consumer retry policy: transient failures are retried with backoff up to {@code maxAttempts};
 * permanent failures (business rejections, malformed payloads) go to the DLQ immediately.
 *
 * @param maxAttempts deliveries before dead-lettering
 * @param backoff delay between redeliveries
 * @param transientFailure classifier of transient failures
 */
public record ConsumerRetryPolicy(
    int maxAttempts, Backoff backoff, Predicate<Throwable> transientFailure) {

  public ConsumerRetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    Objects.requireNonNull(backoff);
    Objects.requireNonNull(transientFailure);
  }

  public static ConsumerRetryPolicy defaults() {
    return new ConsumerRetryPolicy(
        5,
        Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(5)),
        ConsumerRetryPolicy::isTransientDefault);
  }

  static boolean isTransientDefault(Throwable t) {
    var seen = new IdentityHashMap<Throwable, Boolean>();
    for (Throwable cause = t; cause != null; cause = cause.getCause()) {
      if (seen.size() >= 64 || seen.put(cause, Boolean.TRUE) != null) return false;
      if (cause instanceof PayloadException || cause instanceof IllegalArgumentException)
        return false;
      if (cause instanceof AxizException ax && !ax.category().retryable()) return false;
    }
    // Unknown failures keep the bounded delivery policy. Wrapping a known permanent/uncertain
    // failure must never turn it into an automatic retry.
    return true;
  }

  /** Decision after a failed delivery number {@code attempt} (1-based). */
  public DeliveryDecision onFailure(Throwable failure, int attempt) {
    if (!transientFailure.test(failure)) {
      return DeliveryDecision.DEAD_LETTER;
    }
    return attempt >= maxAttempts ? DeliveryDecision.DEAD_LETTER : DeliveryDecision.RETRY;
  }

  public Duration redeliveryDelay(int attempt) {
    return backoff.delay(attempt);
  }
}
