package pe.axiz.core.http;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.resilience.RetryDecider;

/** Decisión de reintento para una llamada HTTP saliente según la configuración de un cliente. */
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
