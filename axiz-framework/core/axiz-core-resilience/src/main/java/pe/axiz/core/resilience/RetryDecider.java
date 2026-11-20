package pe.axiz.core.resilience;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.tx.CommittedTransactionOutcome;
import pe.axiz.core.commons.tx.UnknownTransactionOutcome;
import pe.axiz.core.errors.AxizException;

/**
 * Decides whether a failed call may be repeated. Only idempotent operations (safe HTTP methods,
 * PUT, DELETE, or any call that carries an {@code Idempotency-Key}) are retried, and only on
 * transient failures. A business rejection is never retried.
 */
public final class RetryDecider {

  private static final Set<String> IDEMPOTENT_METHODS =
      Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE");

  private RetryDecider() {}

  public static boolean isIdempotent(String httpMethod, @Nullable String idempotencyKey) {
    return IDEMPOTENT_METHODS.contains(httpMethod.toUpperCase(Locale.ROOT))
        || (idempotencyKey != null && !idempotencyKey.isBlank());
  }

  /** Transient: timeouts, connection failures, and errors whose category is retryable. */
  public static boolean isTransient(Throwable failure) {
    var seen = new IdentityHashMap<Throwable, Boolean>();
    boolean transientFailure = false;
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (seen.size() >= 64 || seen.put(t, Boolean.TRUE) != null) return false;
      if (t instanceof UnknownTransactionOutcome || t instanceof CommittedTransactionOutcome)
        return false;
      if (t instanceof AxizException axiz) {
        if (!axiz.category().retryable()) return false;
        transientFailure = true;
      }
      if (t instanceof TimeoutException
          || t instanceof SocketTimeoutException
          || t instanceof HttpTimeoutException
          || t instanceof ConnectException) {
        transientFailure = true;
      }
      if (t instanceof IOException) {
        if (t.getClass().getSimpleName().contains("SSL")) return false;
        transientFailure = true;
      }
    }
    return transientFailure;
  }

  public static boolean shouldRetry(
      String httpMethod, @Nullable String idempotencyKey, Throwable failure) {
    return isIdempotent(httpMethod, idempotencyKey) && isTransient(failure);
  }
}
