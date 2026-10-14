package pe.axiz.core.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter kept in memory: for local use, tests and single-replica services. Use
 * the Redis implementation for distributed limits.
 */
public final class InMemoryRateLimiter implements RateLimiter {

  private final Clock clock;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public InMemoryRateLimiter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Decision tryAcquire(String key, long limit, Duration window) {
    long now = clock.millis();
    long windowMs = window.toMillis();
    long start = now - Math.floorMod(now, windowMs);
    Window w =
        windows.compute(
            key,
            (k, current) ->
                current == null || current.start != start
                    ? new Window(start, 1)
                    : new Window(start, current.count + 1));
    if (windows.size() > 100_000) {
      windows.entrySet().removeIf(e -> e.getValue().start < start);
    }
    if (w.count <= limit) {
      return new Decision(true, limit - w.count, Duration.ZERO);
    }
    return new Decision(false, 0, Duration.ofMillis(start + windowMs - now));
  }

  private record Window(long start, long count) {}
}
