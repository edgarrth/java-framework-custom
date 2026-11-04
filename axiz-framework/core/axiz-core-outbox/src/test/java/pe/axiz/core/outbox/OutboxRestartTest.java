package pe.axiz.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.core.commons.jdbc.JdbcTransactions;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.resilience.Backoff;

class OutboxRestartTest {
  @TempDir Path directory;

  @Test
  void pendingEventsSurviveDatabaseReopenAndDrainOnlyOnce() throws Exception {
    String url = "jdbc:h2:file:" + directory.resolve("outbox") + ";MODE=PostgreSQL";
    var ds = new JdbcDataSource();
    ds.setURL(url);
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var in = JdbcOutboxStore.class.getResourceAsStream("/axiz/sql/outbox-h2.sql")) {
      assertNotNull(in);
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
        if (!sql.replaceAll("--.*", "").isBlank()) s.execute(sql);
    }
    var first = new JdbcOutboxStore(new JdbcTransactions(ds), "axiz_outbox", Dialect.H2);
    var event =
        new EventEnvelope(
            "payment-1",
            "urn:payments",
            "paid.v1",
            Instant.now(),
            "a-1",
            "application/json",
            new byte[0],
            Map.of());
    new OutboxWriter(first, Clock.systemUTC()).write("kafka:payments", event, "payment");
    assertEquals(1, first.countPending());
    var reopened = new JdbcDataSource();
    reopened.setURL(url);
    var tx = new JdbcTransactions(reopened);
    var restarted = new JdbcOutboxStore(tx, "axiz_outbox", Dialect.H2);
    var published = new ArrayList<EventEnvelope>();
    var relay =
        new OutboxRelay(
            restarted,
            (destination, message) -> {
              published.add(message);
              return new PublishResult(message.id(), destination, "ack");
            },
            tx,
            Clock.systemUTC(),
            10,
            3,
            Backoff.fixed(Duration.ofSeconds(1)));
    relay.relayOnce();
    relay.relayOnce();
    assertEquals(0, restarted.countPending());
    assertEquals(java.util.List.of(event), published);
  }
}
