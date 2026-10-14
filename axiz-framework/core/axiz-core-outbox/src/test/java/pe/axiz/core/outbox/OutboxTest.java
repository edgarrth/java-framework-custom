package pe.axiz.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.jdbc.JdbcTransactions;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.resilience.Backoff;

class OutboxTest {

  private JdbcTransactions tx;
  private JdbcOutboxStore store;
  private final MutableClock clock = new MutableClock();
  private final List<String> published = new ArrayList<>();
  private boolean brokerDown;

  private final EventPublisher publisher =
      (destination, event) -> {
        if (brokerDown) {
          throw new PublishException("down", new RuntimeException());
        }
        published.add(destination + ":" + event.id());
        return new PublishResult(event.id(), destination, null);
      };

  @BeforeEach
  void setUp() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        InputStream in = getClass().getResourceAsStream("/axiz/sql/outbox-h2.sql")) {
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.replaceAll("--.*", "").isBlank()) {
          s.execute(sql);
        }
      }
    }
    tx = new JdbcTransactions(ds);
    store = new JdbcOutboxStore(tx, "axiz_outbox", Dialect.H2);
  }

  private EventEnvelope event(String id) {
    return new EventEnvelope(
        id,
        "urn:s",
        "order-placed.v1",
        clock.instant(),
        "agg-1",
        "application/json",
        "{\"amount\":\"10.00\"}".getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  @Test
  void eventIsRecordedOnlyIfTransactionCommits() throws Exception {
    OutboxWriter writer = new OutboxWriter(store, clock);
    tx.inTransaction(
        () -> {
          writer.write("payments.events", event("e-1"), "order");
          return null;
        });
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.inTransaction(
                () -> {
                  writer.write("payments.events", event("e-2"), "order");
                  throw new IllegalStateException("business failure after writing");
                }));
    assertEquals(1, store.countPending());
  }

  @Test
  void relayPublishesInOrderAndMarks() throws Exception {
    OutboxWriter writer = new OutboxWriter(store, clock);
    writer.write("t", event("e-1"), "order");
    clock.now = clock.now.plusMillis(1);
    writer.write("t", event("e-2"), "order");
    OutboxRelay relay =
        new OutboxRelay(store, publisher, tx, clock, 10, 3, Backoff.fixed(Duration.ofSeconds(10)));
    OutboxRelay.RunResult r = relay.relayOnce();
    assertEquals(2, r.published());
    assertEquals(List.of("t:e-1", "t:e-2"), published);
    assertEquals(0, store.countPending());
    assertEquals(0, relay.relayOnce().total());
    clock.now = clock.now.plus(Duration.ofDays(8));
    assertEquals(2, store.purgePublished(clock.now.minus(Duration.ofDays(7))));
  }

  @Test
  void failuresAreRetriedWithBackoffThenMarkedFailed() throws Exception {
    new OutboxWriter(store, clock).write("t", event("e-1"), null);
    OutboxRelay relay =
        new OutboxRelay(store, publisher, tx, clock, 10, 2, Backoff.fixed(Duration.ofSeconds(30)));
    brokerDown = true;
    assertEquals(1, relay.relayOnce().retried());
    assertEquals(0, relay.relayOnce().total(), "not due before the backoff");
    clock.now = clock.now.plusSeconds(31);
    assertEquals(1, relay.relayOnce().failed());
    assertEquals(0, store.countPending());
    try (Connection c = tx.get();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT status, attempts, last_error FROM axiz_outbox")) {
      rs.next();
      assertEquals("FAILED", rs.getString(1));
      assertEquals(2, rs.getInt(2));
      assertTrue(rs.getString(3).contains("down"));
    }
  }

  @Test
  void drainProcessesBacklogInBatches() {
    OutboxWriter writer = new OutboxWriter(store, clock);
    for (int i = 0; i < 25; i++) {
      writer.write("t", event("e-" + i), null);
    }
    assertEquals(
        25,
        new OutboxRelay(store, publisher, tx, clock, 10, 3, Backoff.fixed(Duration.ZERO)).drain());
    assertThrows(
        IllegalArgumentException.class,
        () -> new OutboxRelay(store, publisher, tx, clock, 0, 3, Backoff.fixed(Duration.ZERO)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JdbcOutboxStore(tx, "bad name", Dialect.POSTGRESQL));
  }

  @Test
  void sqlErrorsAreWrapped() {
    JdbcOutboxStore broken = new JdbcOutboxStore(tx, "missing_table", Dialect.H2);
    assertThrows(OutboxException.class, broken::countPending);
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
