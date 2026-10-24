package pe.axiz.core.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nonce store for a single instance (tests, local runs, one-replica tools). Bounded: expired
 * entries are swept when the limit is reached and, if it is still full, new nonces are refused by
 * throwing, so the verifier fails closed instead of forgetting nonces that are still valid.
 */
public final class InMemoryNonceStore implements NonceStore {

  private final Map<String, Instant> used = new ConcurrentHashMap<>();
  private final int maxEntries;
  private final Clock clock;

  public InMemoryNonceStore(int maxEntries, Clock clock) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be positive");
    }
    this.maxEntries = maxEntries;
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public boolean markUsed(String nonce, Duration ttl) {
    Instant now = clock.instant();
    if (used.size() >= maxEntries) {
      used.values().removeIf(expiry -> !expiry.isAfter(now));
      if (used.size() >= maxEntries) {
        throw new IllegalStateException("Nonce store is full");
      }
    }
    AtomicBoolean first = new AtomicBoolean();
    used.compute(
        nonce,
        (k, expiry) -> {
          if (expiry != null && expiry.isAfter(now)) {
            return expiry;
          }
          first.set(true);
          return now.plus(ttl);
        });
    return first.get();
  }
}
