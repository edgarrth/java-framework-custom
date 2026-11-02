package pe.axiz.core.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Local CPU-only rate limiter; no remote I/O, threads or scheduler offloading. */
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
