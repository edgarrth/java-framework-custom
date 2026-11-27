package pe.axiz.spring.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.resilience.Backoff;

/** El bucle del relay, el cableado del relay reactivo y el gauge de pendientes. */
class OutboxWiringTest {

  /** Almacén sin nada pendiente que cuenta las purgas y que puede hacerse fallar. */
  static final class CountingStore implements OutboxStore {

    public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
      locks.incrementAndGet();
      if (failing.get()) throw new IllegalStateException("database down");
      return List.of();
    }

    public boolean markPublished(String id, String owner, Instant at) {
      return false;
    }

    public boolean markFailed(
        String id,
        String owner,
        int attempts,
        Instant next,
        @Nullable String error,
        boolean giveUp) {
      return false;
    }

    public boolean retry(String id) {
      return false;
    }

    public boolean skip(String id, String reason) {
      return false;
    }

    public long countSkipped() {
      return 0;
    }

    public long countBlockedAggregates() {
      return 0;
    }

    public void verifySchema() {}

    final AtomicInteger locks = new AtomicInteger();
    final AtomicInteger purges = new AtomicInteger();
    final AtomicBoolean failing = new AtomicBoolean();
    volatile long pending = 3;

    @Override
    public void append(OutboxRecord record) {}

    @Override
    public int purgePublished(Instant before) {
      purges.incrementAndGet();
      return 0;
    }

    @Override
    public long countPending() {
      if (failing.get()) {
        throw new IllegalStateException("database down");
      }
      return pending;
    }
  }

  /**
   * Reloj que avanza una hora en cada lectura, de modo que en cada ejecución corresponde una purga.
   */
  static final class RacingClock extends Clock {
    private Instant now = Instant.parse("2026-10-23T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public synchronized Instant instant() {
      now = now.plus(Duration.ofHours(1));
      return now;
    }
  }

  /** Ejecuta el trabajo tal cual, sin transacción. */
  private static final TransactionRunner DIRECT =
      new TransactionRunner() {
        @Override
        public <T> T inTransaction(Callable<T> work) throws Exception {
          return work.call();
        }
      };

  private static final EventPublisher NOWHERE =
      new EventPublisher() {
        @Override
        public PublishResult publish(String destination, EventEnvelope event) {
          return new PublishResult(event.id(), destination, null);
        }
      };

  private static AxizOutboxProperties.Relay relay(int batchSize) {
    return AxizOutboxProperties.Relay.builder()
        .enabled(true)
        .pollInterval(Duration.ofMillis(5))
        .batchSize(batchSize)
        .maxAttempts(3)
        .initialDelay(Duration.ofMillis(10))
        .maxDelay(Duration.ofSeconds(1))
        .retention(Duration.ofDays(7))
        .purgeInterval(Duration.ofMinutes(30))
        .leaseDuration(Duration.ofMinutes(3))
        .claimAllowance(Duration.ofSeconds(2))
        .publishBudget(null)
        .build();
  }

  private static void await(BooleanSupplier condition) throws Exception {
    Awaitility.await()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(5))
        .atMost(Duration.ofSeconds(5))
        .until(condition::getAsBoolean);
  }

  @Test
  void theRelayLoopPurgesOnScheduleAndSurvivesAFailingStore() throws Exception {
    CountingStore store = new CountingStore();
    RacingClock clock = new RacingClock();
    OutboxRelay relay =
        OutboxRelay.builder(store, NOWHERE, DIRECT, clock)
            .batchSize(10)
            .maxAttempts(3)
            .backoff(Backoff.exponential(Duration.ofMillis(10), Duration.ofSeconds(1)))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    OutboxRelayRunner runner =
        new OutboxRelayRunner(relay, store, ObservationRegistry.NOOP, relay(10), clock);
    assertEquals(0, runner.runOnce(), "nothing due");
    assertFalse(runner.isRunning());

    runner.start();
    try {
      assertTrue(runner.isRunning());
      await(() -> store.purges.get() >= 2);
      store.failing.set(true);
      int before = store.locks.get();
      await(() -> store.locks.get() > before + 1);
      assertThrows(IllegalStateException.class, runner::runOnce);
      store.failing.set(false);
    } finally {
      runner.stop();
    }
    assertFalse(runner.isRunning());
  }

  @Test
  void aFullBatchIsFollowedImmediatelyByTheNextOne() throws Exception {
    CountingStore store = new CountingStore();
    OutboxRelay relay =
        OutboxRelay.builder(store, NOWHERE, DIRECT, Clock.systemUTC())
            .batchSize(10)
            .maxAttempts(3)
            .backoff(Backoff.exponential(Duration.ofMillis(10), Duration.ofSeconds(1)))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    // con un tamaño de lote cero en la configuración del runner, cada ejecución cuenta como llena:
    // sin espera entre ejecuciones
    OutboxRelayRunner runner =
        new OutboxRelayRunner(relay, store, ObservationRegistry.NOOP, relay(0), Clock.systemUTC());
    runner.start();
    try {
      await(() -> store.locks.get() > 50);
    } finally {
      runner.stop();
    }
  }

  @Test
  void theServletGaugeReportsNothingWhenTheStoreCannotAnswer() throws Exception {
    CountingStore store = new CountingStore();
    MeterBinder binder =
        new AxizOutboxAutoConfiguration.MetricsConfiguration()
            .axizOutboxMetrics(
                store,
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        java.util.Map.of("clock", new RacingClock()))
                    .getBeanProvider(Clock.class));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    binder.bindTo(registry);
    await(() -> registry.get("axiz.outbox.pending").gauge().value() == 3.0);
    store.failing.set(true);
    await(() -> Double.isNaN(registry.get("axiz.outbox.pending").gauge().value()));
  }
}
