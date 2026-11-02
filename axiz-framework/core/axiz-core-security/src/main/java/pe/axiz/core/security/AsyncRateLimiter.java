package pe.axiz.core.security;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Non-blocking rate limiter, with an explicit policy when the backing store fails. */
@FunctionalInterface
public interface AsyncRateLimiter {
  CompletionStage<RateLimiter.Decision> tryAcquire(
      String key, long limit, Duration window, boolean failOpen);
}
