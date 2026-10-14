package pe.axiz.core.security;

import java.time.Duration;

/** Rate limiter port; the key always comes from the validated token, never from a client header. */
public interface RateLimiter {

  /**
   * Tries to consume one permit.
   *
   * @param key limiter key, see {@link RateLimitKeys}
   * @param limit permits per window
   * @param window window length
   * @return the decision
   */
  Decision tryAcquire(String key, long limit, Duration window);

  /**
   * Decision of the limiter.
   *
   * @param allowed whether the request may proceed
   * @param remaining permits left in the window
   * @param retryAfter time until a permit becomes available, when rejected
   */
  record Decision(boolean allowed, long remaining, Duration retryAfter) {}
}
