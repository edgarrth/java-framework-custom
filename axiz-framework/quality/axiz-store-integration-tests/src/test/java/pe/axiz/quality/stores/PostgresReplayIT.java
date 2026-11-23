package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.adapter.outbox.jdbc.Dialect;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.core.inbox.InboxDeduplicator;
import pe.axiz.core.messaging.ConsumerRetryPolicy;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.messaging.JacksonPayloadCodec;
import pe.axiz.test.support.MutableClock;
import tools.jackson.databind.json.JsonMapper;

/**
 * La reentrega al menos una vez tras un ack perdido preserva un único efecto de negocio entre
 * workers recreados.
 */
class PostgresReplayIT {
  private static final PostgreSQLContainer POSTGRES = TestContainerImages.postgres();

  @BeforeAll
  static void start() throws Exception {
    Docker.require();
    POSTGRES.start();
    try (var connection = source().getConnection();
        var statement = connection.createStatement()) {
      for (var resource :
          List.of("/axiz/sql/inbox-postgresql.sql", "/axiz/sql/outbox-postgresql.sql")) {
        try (InputStream schema = JdbcInboxStore.class.getResourceAsStream(resource)) {
          if (schema == null) throw new IllegalStateException("Missing schema " + resource);
          for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";"))
            if (!sql.replaceAll("--.*", "").isBlank()) statement.execute(sql);
        }
      }
      statement.execute(
          "CREATE TABLE business_effects (id BIGSERIAL PRIMARY KEY, payload TEXT NOT NULL)");
    }
  }

  private static PGSimpleDataSource source() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void lostAckThenRecreatedRelayAndConsumerCannotApplyThePaymentTwice() throws Exception {
    var clock = new MutableClock(Instant.now(), ZoneOffset.UTC);
    var tx = new JdbcTransactions(source());
    var store = new JdbcOutboxStore(tx, tx, "axiz_outbox", Dialect.POSTGRESQL);
    var event =
        EventEnvelope.builder()
            .id("payment-1")
            .source("urn:axiz:payments")
            .type("paid.v1")
            .time(clock.instant())
            .subject("account-1")
            .dataContentType("application/json")
            .data("\"payment\"".getBytes(StandardCharsets.UTF_8))
            .extensions(Map.of())
            .build();
    new OutboxWriter(store, clock).write("kafka:payments", event, "payment");
    var deliveries = new ArrayList<String>();
    var before =
        OutboxRelay.builder(store, consumer(clock, deliveries, true), tx, clock)
            .batchSize(10)
            .maxAttempts(3)
            .backoff(Backoff.fixed(Duration.ofHours(1)))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    assertEquals(1, before.relayOnce().retried());
    assertEquals(1, store.countPending());
    assertEquals(1, effects());
    // Las identidades nuevas de datasource garantizan que el receptor confirma de forma
    // independiente del emisor.
    // No se conserva ningún objeto worker ni inbox de la primera entrega.
    var restartedTx = new JdbcTransactions(source());
    var restartedStore =
        new JdbcOutboxStore(restartedTx, restartedTx, "axiz_outbox", Dialect.POSTGRESQL);
    var restarted =
        OutboxRelay.builder(restartedStore, consumer(clock, deliveries, false), restartedTx, clock)
            .batchSize(10)
            .maxAttempts(3)
            .backoff(Backoff.fixed(Duration.ofHours(1)))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    clock.advance(Duration.ofHours(1));
    assertEquals(
        0,
        restarted.relayOnce().published(),
        "Advancing the application clock cannot make a database retry eligible");
    // Se establece la elegibilidad en la base de datos de forma explícita; esta regresión no espera
    // tiempo de reloj real.
    try (var connection = source().getConnection();
        var statement = connection.createStatement()) {
      assertEquals(
          1,
          statement.executeUpdate(
              "UPDATE axiz_outbox SET next_attempt_at=statement_timestamp()-interval '1 second' "
                  + "WHERE id='payment-1' AND status='PENDING'"));
    }
    assertEquals(1, restarted.relayOnce().published());
    assertEquals(0, restarted.relayOnce().total());
    assertEquals(0, restartedStore.countPending());
    assertEquals(1, effects());
    assertEquals(List.of("urn:axiz:payments/payment-1", "urn:axiz:payments/payment-1"), deliveries);
  }

  private static EventPublisher consumer(
      MutableClock clock, List<String> deliveries, boolean loseAck) {
    return (destination, event) -> {
      deliveries.add(event.source() + "/" + event.id());
      var tx = new JdbcTransactions(source());
      var inbox = new JdbcInboxStore(tx, "axiz_inbox");
      var handler =
          new EventHandler<String>() {
            public String eventType() {
              return "paid.v1";
            }

            public Class<String> payloadType() {
              return String.class;
            }

            public void handle(String payload, EventEnvelope envelope) throws Exception {
              var connection = tx.get();
              try (var statement =
                  connection.prepareStatement("INSERT INTO business_effects(payload) VALUES (?)")) {
                statement.setString(1, payload);
                statement.executeUpdate();
              } finally {
                tx.release(connection);
              }
            }
          };
      var dispatcher =
          new EventDispatcher(
              "payment-ledger",
              List.of(handler),
              new JacksonPayloadCodec(JsonMapper.builder().build()),
              new InboxDeduplicator(inbox, Duration.ofSeconds(10), Duration.ofDays(1), clock),
              ConsumerRetryPolicy.defaults(),
              false);
      try {
        assertEquals(DeliveryDecision.ACK, tx.inTransaction(() -> dispatcher.dispatch(event, 1)));
      } catch (Exception failure) {
        throw new PublishException("Consumer failed", failure);
      }
      if (loseAck)
        throw new PublishException(
            "Acknowledgement lost after consumer commit", new TimeoutException());
      return new PublishResult(event.id(), destination, "ack");
    };
  }

  private static int effects() throws Exception {
    try (var connection = source().getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM business_effects")) {
      rows.next();
      return rows.getInt(1);
    }
  }
}
