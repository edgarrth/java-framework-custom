package pe.axiz.adapter.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.jdbc.JdbcTransactions;

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
    var first =
        new JdbcOutboxStore(
            new JdbcTransactions(ds), new JdbcTransactions(ds), "axiz_outbox", Dialect.H2);
    var event =
        EventEnvelope.builder()
            .id("payment-1")
            .source("urn:payments")
            .type("paid.v1")
            .time(Instant.now())
            .subject("a-1")
            .dataContentType("application/json")
            .data(new byte[0])
            .extensions(Map.of())
            .build();
    new OutboxWriter(first, Clock.systemUTC()).write("kafka:payments", event, "payment");
    assertEquals(1, first.countPending());
    var reopened = new JdbcDataSource();
    reopened.setURL(url);
    var tx = new JdbcTransactions(reopened);
    var restarted = new JdbcOutboxStore(tx, tx, "axiz_outbox", Dialect.H2);
    var published = new ArrayList<EventEnvelope>();
    var relay =
        OutboxRelay.builder(
                restarted,
                (destination, message) -> {
                  published.add(message);
                  return new PublishResult(message.id(), destination, "ack");
                },
                tx,
                Clock.systemUTC())
            .batchSize(10)
            .maxAttempts(3)
            .backoff(Backoff.fixed(Duration.ofSeconds(1)))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    relay.relayOnce();
    relay.relayOnce();
    assertEquals(0, restarted.countPending());
    assertEquals(List.of(event), published);
  }
}
