package pe.axiz.core.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Limitador de tasa local solo de CPU; sin E/S remota, hilos ni delegación a un scheduler. */
public final class InMemoryAsyncRateLimiter implements AsyncRateLimiter {
  private final InMemoryRateLimiter counters;

  public InMemoryAsyncRateLimiter(Clock clock) {
    counters = new InMemoryRateLimiter(clock);
  }

  @Override
  public CompletionStage<RateLimiter.Decision> tryAcquire(
      String key, long limit, Duration window, boolean failOpen) {
    return CompletableFuture.completedFuture(counters.tryAcquire(key, limit, window));
  }
}
