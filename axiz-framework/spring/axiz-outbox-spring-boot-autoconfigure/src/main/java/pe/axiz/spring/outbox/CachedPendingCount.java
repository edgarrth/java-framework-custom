package pe.axiz.spring.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.outbox.OutboxStore;

/**
 * Pending-record count for the {@code axiz.outbox.pending} gauge when the store blocks on a
 * reactive driver: a scrape never runs the query on its own thread (which may be an event loop); it
 * reads the last value and, when it is older than {@code maxAge}, starts one refresh on a virtual
 * thread. Reports NaN until the first refresh completes.
 */
public final class CachedPendingCount {

  private static final Logger LOG = LoggerFactory.getLogger(CachedPendingCount.class);

  private record Sample(double value, Instant at) {}

  private final OutboxStore store;
  private final Duration maxAge;
  private final Clock clock;
  private final AtomicReference<Sample> last =
      new AtomicReference<>(new Sample(Double.NaN, Instant.EPOCH));
  private final AtomicBoolean refreshing = new AtomicBoolean();

  public CachedPendingCount(OutboxStore store, Duration maxAge, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.maxAge = Objects.requireNonNull(maxAge);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Last known value; triggers a background refresh when stale. */
  public double value() {
    Sample s = last.get();
    if (s.at().plus(maxAge).isBefore(clock.instant()) && refreshing.compareAndSet(false, true)) {
      Thread.ofVirtual().name("axiz-outbox-pending").start(this::refresh);
    }
    return s.value();
  }

  /** Queries the store now (blocking). */
  void refresh() {
    try {
      last.set(new Sample(store.countPending(), clock.instant()));
    } catch (RuntimeException e) {
      LOG.debug("Outbox pending count failed: {}", e.toString());
      last.set(new Sample(Double.NaN, clock.instant()));
    } finally {
      refreshing.set(false);
    }
  }
}
