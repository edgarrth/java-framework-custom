package pe.axiz.spring.outbox.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.spring.outbox.AxizOutboxAutoConfiguration;
import pe.axiz.spring.outbox.OutboxRelayRunner;
import pe.axiz.test.support.r2dbc.FakeR2dbc;

class OutboxReactiveWiringTest {
  /** A store with nothing due that counts purges and can be made to fail. */
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

  private static void await(BooleanSupplier condition) throws Exception {
    Awaitility.await()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(5))
        .atMost(Duration.ofSeconds(5))
        .until(condition::getAsBoolean);
  }

  @Test
  void reactiveServicesGetTheRelayAndThePendingGauge() throws Exception {
    FakeR2dbc db = new FakeR2dbc();
    CountingStore store = new CountingStore();
    List<String> sent = new CopyOnWriteArrayList<>();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizOutboxAutoConfiguration.class, AxizOutboxR2dbcAutoConfiguration.class))
        .withPropertyValues(
            "axiz.outbox.store=r2dbc",
            "axiz.outbox.relay.poll-interval=20ms",
            "axiz.outbox.relay.publish-budget=1ms")
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
                    OutboxR2dbcTest.CODEC,
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
}
