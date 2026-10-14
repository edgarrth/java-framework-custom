package pe.axiz.core.http;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.resilience.RetryDecider;

/** Retry decision for an outbound HTTP call under a client's settings. */
public final class HttpRetryRules {

  private HttpRetryRules() {}

  public static boolean shouldRetry(
      ClientSettings settings,
      String method,
      @Nullable String idempotencyKey,
      Throwable failure,
      int attempt) {
    return settings.retry() == ClientSettings.RetryMode.IDEMPOTENT_ONLY
        && attempt < settings.maxAttempts()
        && RetryDecider.shouldRetry(method, idempotencyKey, failure);
  }
}
