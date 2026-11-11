package pe.axiz.spring.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.r2dbc.R2dbcRow;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.RowMapper;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class OutboxR2dbcTest {

  private static final Duration T = Duration.ofSeconds(5);

  /** What the R2DBC starter provides: SQL that joins Spring's reactive transaction. */
  record TxSql(DatabaseClient client) implements ReactiveSql {
    @Override
    public Mono<Long> update(String sql, SqlParams params) {
      return bind(client.sql(sql), params).fetch().rowsUpdated();
    }

    @Override
    public <T> Flux<T> query(String sql, SqlParams params, RowMapper<T> mapper) {
      return bind(client.sql(sql), params).map((r, m) -> mapper.map(new R2dbcRow(r))).all();
    }

    private static DatabaseClient.GenericExecuteSpec bind(
        DatabaseClient.GenericExecuteSpec spec, SqlParams params) {
      DatabaseClient.GenericExecuteSpec s = spec;
      for (SqlParams.Param p : params.params()) {
        Object v = p.value();
        s = v == null ? s.bindNull(p.name(), p.type()) : s.bind(p.name(), v);
      }
      return s;
    }
  }

  @Test
  void eventsJoinTheReactiveTransactionOfTheBusinessChange() {
    FakeR2dbc db =
        new FakeR2dbc()
            .onQuery("WHERE 1=0", b -> List.of())
            .onQuery("RETURNING last_seq", b -> List.of(Map.of("last_seq", 1L)))
            .onUpdate("UPDATE payments", b -> 1L)
            .onUpdate("INSERT INTO axiz_outbox", b -> 1L);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizOutboxR2dbcAutoConfiguration.class))
        .withPropertyValues("axiz.outbox.relay.enabled=false")
        .withBean(ConnectionFactory.class, () -> db)
        .withBean(ReactiveSql.class, () -> new TxSql(DatabaseClient.create(db)))
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(db))
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
              assertInstanceOf(R2dbcOutboxStore.class, ctx.getBean(OutboxStore.class));
              assertFalse(ctx.containsBean("axizOutboxRelayRunner"));
              ReactiveTransactionalEvents events = ctx.getBean(ReactiveTransactionalEvents.class);
              ReactiveSql sql = ctx.getBean(ReactiveSql.class);
              Mono<EventEnvelope> outside =
                  events.enqueue("payments", "e-0", "x.v1", "payment", "p-0", "x");
              assertThrows(IllegalStateException.class, () -> outside.block(T));
              assertTrue(db.executions().stream().allMatch(e -> e.sql().contains("WHERE 1=0")));

              TransactionalOperator tx =
                  TransactionalOperator.create(ctx.getBean(ReactiveTransactionManager.class));
              EventEnvelope stored =
                  tx.transactional(
                          sql.update(
                                  "UPDATE payments SET status = :s WHERE id = :id",
                                  SqlParams.of().bind("s", "PAID").bind("id", "p-1"))
                              .then(
                                  events.enqueue(
                                      "payments",
                                      "e-1",
                                      "payment-paid.v1",
                                      "payment",
                                      "p-1",
                                      "hello")))
                      .block(T);
              assertEquals("e-1", stored.id());
              int connection = db.last("UPDATE payments").connection();
              assertEquals(connection, db.last("INSERT INTO axiz_outbox").connection());
              assertTrue(db.events().contains("commit:" + connection));
            });
  }

  @Test
  void pendingGaugeNeverQueriesOnTheScrapeThread() throws Exception {
    AtomicInteger queries = new AtomicInteger();
    Thread caller = Thread.currentThread();
    OutboxStore store =
        new OutboxStore() {

          public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
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

          @Override
          public void append(OutboxRecord record) {}

          @Override
          public int purgePublished(Instant before) {
            return 0;
          }

          @Override
          public long countPending() {
            assertFalse(Thread.currentThread() == caller, "queried on the scrape thread");
            return 40 + queries.incrementAndGet();
          }
        };
    CachedPendingCount pending =
        new CachedPendingCount(store, Duration.ofMinutes(1), Clock.systemUTC());
    assertTrue(Double.isNaN(pending.value()));
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !Double.isNaN(pending.value()));
    assertEquals(41.0, pending.value());
    assertEquals(1, queries.get());
  }
}
