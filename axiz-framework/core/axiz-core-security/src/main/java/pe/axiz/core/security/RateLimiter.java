package pe.axiz.core.security;

import java.time.Duration;

/**
 * Rate limiter port. Caller keys come from validated identities; explicitly configured anonymous
 * quotas use the transport peer address. Client headers never supply either identity.
 */
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
   * Tries to consume one permit with an explicit policy for when the limiter store is unavailable:
   * a route group can fail closed (financial operations, callbacks) while others fail open. A
   * limiter whose store cannot fail (in memory) ignores the policy.
   *
   * @param failOpen whether the request passes when the store cannot answer
   */
  default Decision tryAcquire(String key, long limit, Duration window, boolean failOpen) {
    return tryAcquire(key, limit, window);
  }

  /**
   * Decision of the limiter.
   *
   * @param allowed whether the request may proceed
   * @param remaining permits left in the window
   * @param retryAfter time until a permit becomes available, when rejected
   */
  record Decision(boolean allowed, long remaining, Duration retryAfter) {}
}
