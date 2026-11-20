package pe.axiz.core.inbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.messaging.Deduplicator;

/**
 * Connects the inbox to the event dispatcher: a message is processed once per consumer within the
 * retention period. The lease protects against two deliveries processing at the same time, the
 * owner token against a late delivery changing the registration of the one that took it over.
 */
public final class InboxDeduplicator implements Deduplicator {

  private final InboxStore store;
  private final Duration lease;
  private final Duration retention;
  private final Clock clock;

  public InboxDeduplicator(InboxStore store, Duration lease, Duration retention, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.lease = Objects.requireNonNull(lease);
    this.retention = Objects.requireNonNull(retention);
    this.clock = Objects.requireNonNull(clock);
    if (lease.isNegative() || lease.isZero()) {
      throw new IllegalArgumentException("lease must be positive");
    }
    if (retention.compareTo(lease) <= 0) {
      throw new IllegalArgumentException("retention must exceed the processing lease");
    }
  }

  @Override
  public Claim claim(String consumer, String messageKey) {
    Instant now = clock.instant();
    String token = Claim.newToken();
    InboxStore.Acquisition a =
        store.tryAcquire(consumer, messageKey, token, now, now.plus(lease), now.plus(retention));
    return switch (a.status()) {
      case ACQUIRED -> Claim.acquired(token);
      case PROCESSED -> Claim.alreadyProcessed();
      case BUSY ->
          Claim.busy(a.leaseUntil() == null ? lease : Duration.between(now, a.leaseUntil()));
    };
  }

  @Override
  public boolean completed(String consumer, String messageKey, String token) {
    return store.markProcessed(consumer, messageKey, token, clock.instant());
  }

  @Override
  public boolean failed(String consumer, String messageKey, String token) {
    return store.release(consumer, messageKey, token);
  }
}
