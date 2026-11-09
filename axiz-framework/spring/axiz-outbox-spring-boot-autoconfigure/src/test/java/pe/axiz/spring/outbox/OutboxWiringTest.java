package pe.axiz.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.test.support.r2dbc.FakeR2dbc;

/** The relay loop, the reactive relay wiring and the pending gauge. */
class OutboxWiringTest {

  /** A store with nothing due that counts purges and can be made to fail. */
  static final class CountingStore implements OutboxStore {

    public java.util.List<OutboxRecord> claimDue(int size, java.time.Duration lease, String owner) {
      return lockDue(size, Instant.EPOCH);
    }

    public boolean markPublished(String id, String owner, Instant at) {
      return false;
    }

    public boolean markFailed(
        String id,
        String owner,
        int attempts,
        Instant next,
        @org.jspecify.annotations.Nullable String error,
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
    public List<OutboxRecord> lockDue(int size, Instant now) {
      locks.incrementAndGet();
      if (failing.get()) {
        throw new IllegalStateException("database down");
      }
      return List.of();
    }

    @Override
    public void markPublished(String id, Instant at) {}

    @Override
    public void markFailed(
        String id, int attempts, Instant nextAttemptAt, @Nullable String error, boolean giveUp) {}

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

  /** A clock that moves one hour forward on every reading, so every run is due for a purge. */
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

  /** Runs the work as it is, without a transaction. */
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
    return new AxizOutboxProperties.Relay(
        true,
        Duration.ofMillis(5),
        batchSize,
        3,
        Duration.ofMillis(10),
        Duration.ofSeconds(1),
        Duration.ofDays(7),
        Duration.ofMinutes(30));
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    org.awaitility.Awaitility.await()
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
        new OutboxRelay(
            store,
            NOWHERE,
            DIRECT,
            clock,
            10,
            3,
            Backoff.exponential(Duration.ofMillis(10), Duration.ofSeconds(1)),
            Duration.ofMinutes(3),
            Duration.ofMillis(1));
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
        new OutboxRelay(
            store,
            NOWHERE,
            DIRECT,
            Clock.systemUTC(),
            10,
            3,
            Backoff.exponential(Duration.ofMillis(10), Duration.ofSeconds(1)),
            Duration.ofMinutes(3),
            Duration.ofMillis(1));
    // with a batch size of zero in the runner settings every run counts as full: no wait between
    // runs
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
  void reactiveServicesGetTheRelayAndThePendingGauge() throws Exception {
    FakeR2dbc db = new FakeR2dbc();
    CountingStore store = new CountingStore();
    List<String> sent = new CopyOnWriteArrayList<>();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizOutboxR2dbcAutoConfiguration.class))
        .withPropertyValues(
            "axiz.outbox.relay.poll-interval=20ms", "axiz.outbox.relay.publish-budget=1ms")
        .withBean(ConnectionFactory.class, () -> db)
        .withBean(ReactiveSql.class, () -> new OutboxR2dbcTest.TxSql(DatabaseClient.create(db)))
        .withBean(OutboxStore.class, () -> store)
        .withBean(
            EventPublisher.class,
            () ->
                (destination, event) -> {
                  sent.add(destination);
                  return new PublishResult(event.id(), destination, null);
                })
        .withBean(
            EventFactory.class,
            () ->
                new EventFactory(
                    "urn:axiz:test",
                    "pe.axiz.test",
                    OutboxTest.CODEC,
                    IdGenerator.uuidV7(),
                    Clock.systemUTC()))
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(OutboxRelay.class);
              OutboxRelayRunner runner = ctx.getBean(OutboxRelayRunner.class);
              assertTrue(runner.isRunning(), "started with the context");
              await(() -> store.locks.get() > 0);

              SimpleMeterRegistry registry = new SimpleMeterRegistry();
              ctx.getBean(MeterBinder.class).bindTo(registry);
              await(() -> registry.get("axiz.outbox.pending").gauge().value() == 3.0);
            });
    assertThat(sent).isEmpty();
  }

  @Test
  void theServletGaugeReportsNothingWhenTheStoreCannotAnswer() {
    CountingStore store = new CountingStore();
    MeterBinder binder =
        new AxizOutboxAutoConfiguration.MetricsConfiguration().axizOutboxMetrics(store);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    binder.bindTo(registry);
    assertEquals(3.0, registry.get("axiz.outbox.pending").gauge().value());
    store.failing.set(true);
    assertTrue(Double.isNaN(registry.get("axiz.outbox.pending").gauge().value()));
  }
}
