package pe.axiz.spring.inbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.spring.foundation.lifecycle.WorkerLifecycle;
import pe.axiz.spring.foundation.lifecycle.WorkerStatus;

/** Deletes expired inbox rows periodically on a virtual thread (JDBC and R2DBC stores). */
public final class InboxPurger implements SmartLifecycle, WorkerStatus {

  private static final Logger LOG = LoggerFactory.getLogger(InboxPurger.class);

  private final InboxStore store;
  private final Duration interval;
  private final Clock clock;
  private final WorkerLifecycle lifecycle;

  public InboxPurger(InboxStore store, Duration interval, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.interval = Objects.requireNonNull(interval);
    if (interval.isNegative() || interval.isZero())
      throw new IllegalArgumentException("Positive purge interval required");
    this.clock = Objects.requireNonNull(clock);
    this.lifecycle =
        new WorkerLifecycle("axiz-inbox-purge", Duration.ZERO, () -> List.of(this::loop), () -> {});
  }

  private void loop() {
    while (lifecycle.accepting()) {
      try {
        Thread.sleep(interval);
        if (!lifecycle.accepting()) return;
        int n = store.purgeExpired(clock.instant());
        LOG.debug("Purged {} expired inbox rows", n);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException failure) {
        LOG.warn("Inbox purge failed; failureType={}", failure.getClass().getSimpleName());
      }
    }
  }

  @Override
  public WorkerStatus.Snapshot workerSnapshot() {
    return lifecycle.workerSnapshot();
  }

  @Override
  public Duration shutdownGrace() {
    return lifecycle.shutdownGrace();
  }

  @Override
  public void start() {
    lifecycle.start();
  }

  @Override
  public void stop() {
    lifecycle.stop();
  }

  @Override
  public void stop(Runnable callback) {
    lifecycle.stop(callback);
  }

  @Override
  public boolean isRunning() {
    return lifecycle.isRunning();
  }
}
