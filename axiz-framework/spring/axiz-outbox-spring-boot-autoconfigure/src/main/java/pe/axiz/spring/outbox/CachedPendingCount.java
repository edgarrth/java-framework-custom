package pe.axiz.spring.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;
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

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "OutboxStore is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final OutboxStore store;

  private final ToLongFunction<OutboxStore> query;
  private final Duration maxAge;
  private final Clock clock;
  private final AtomicReference<Sample> last =
      new AtomicReference<>(new Sample(Double.NaN, Instant.EPOCH));
  private final AtomicBoolean refreshing = new AtomicBoolean();

  public CachedPendingCount(OutboxStore store, Duration maxAge, Clock clock) {
    this(store, maxAge, clock, OutboxStore::countPending);
  }

  public CachedPendingCount(
      OutboxStore store, Duration maxAge, Clock clock, ToLongFunction<OutboxStore> query) {
    this.query = Objects.requireNonNull(query);
    this.store = Objects.requireNonNull(store);
    this.maxAge = Objects.requireNonNull(maxAge);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Last known value; triggers a background refresh when stale. */
  public double value() {
    Sample s = last.get();
    if (s.at().plus(maxAge).isBefore(clock.instant()) && refreshing.compareAndSet(false, true)) {
      // A previous refresh may have finished between reading the sample and acquiring the flag.
      s = last.get();
      if (s.at().plus(maxAge).isBefore(clock.instant())) {
        Thread.ofVirtual().name("axiz-outbox-pending").start(this::refresh);
      } else {
        refreshing.set(false);
      }
    }
    return s.value();
  }

  /** Queries the store now (blocking). */
  void refresh() {
    try {
      last.set(new Sample(query.applyAsLong(store), clock.instant()));
    } catch (RuntimeException e) {
      LOG.debug("Outbox pending count failed ({})", e.getClass().getSimpleName());
      last.set(new Sample(Double.NaN, clock.instant()));
    } finally {
      refreshing.set(false);
    }
  }
}
