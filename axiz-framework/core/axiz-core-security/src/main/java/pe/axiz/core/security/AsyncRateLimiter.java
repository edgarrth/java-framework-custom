package pe.axiz.core.security;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Limitador de tasa no bloqueante, con una política explícita para cuando falla el almacén
 * subyacente.
 */
@FunctionalInterface
public interface AsyncRateLimiter {
  CompletionStage<RateLimiter.Decision> tryAcquire(
      String key, long limit, Duration window, boolean failOpen);
}
