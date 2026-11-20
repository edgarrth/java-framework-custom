package pe.axiz.spring.outbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.spring.foundation.lifecycle.WorkerLifecycle;
import pe.axiz.spring.foundation.lifecycle.WorkerStatus;

/**
 * Background relay on a virtual thread: polls, publishes in batches while there is work, sleeps
 * otherwise, and purges published records periodically. Each run is an observation.
 */
public final class OutboxRelayRunner implements SmartLifecycle, WorkerStatus {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayRunner.class);

  private final OutboxRelay relay;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "OutboxStore is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final OutboxStore store;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObservationRegistry is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final ObservationRegistry observations;

  private final AxizOutboxProperties.Relay settings;
  private final Clock clock;
  private final WorkerLifecycle lifecycle;

  public OutboxRelayRunner(
      OutboxRelay relay,
      OutboxStore store,
      ObservationRegistry observations,
      AxizOutboxProperties.Relay settings,
      Clock clock) {
    this.relay = Objects.requireNonNull(relay);
    this.store = Objects.requireNonNull(store);
    this.observations = Objects.requireNonNull(observations);
    this.settings = Objects.requireNonNull(settings);
    this.clock = Objects.requireNonNull(clock);
    this.lifecycle =
        new WorkerLifecycle(
            "axiz-outbox-relay", settings.shutdownGrace(), () -> List.of(this::loop), () -> {});
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

  /** Runs one batch; returns how many records were handled. */
  public int runOnce() {
    return Observation.createNotStarted(AxizObservations.OUTBOX_RELAY, observations)
        .observe(() -> relay.relayOnce().total());
  }

  private void loop() {
    Instant nextPurge = clock.instant().plus(settings.purgeInterval());
    while (lifecycle.accepting() && !Thread.currentThread().isInterrupted()) {
      try {
        int handled = runOnce();
        if (clock.instant().isAfter(nextPurge)) {
          int purged = store.purgePublished(clock.instant().minus(settings.retention()));
          LOG.debug("Purged {} published outbox records", purged);
          nextPurge = clock.instant().plus(settings.purgeInterval());
        }
        if (handled < settings.batchSize()) {
          sleep(settings.pollInterval());
        }
      } catch (RuntimeException e) {
        LOG.warn("Outbox relay run failed; failureType={}", e.getClass().getSimpleName());
        sleep(settings.pollInterval().multipliedBy(5));
      }
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
