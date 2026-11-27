package pe.axiz.spring.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.spring.outbox.AxizOutboxAutoConfiguration;
import pe.axiz.spring.outbox.OutboxRelayRunner;
import pe.axiz.spring.outbox.TransactionalEvents;

class OutboxTest {

  static final PayloadCodec CODEC =
      new PayloadCodec() {
        @Override
        public String contentType() {
          return "text/plain";
        }

        @Override
        public byte[] encode(Object payload) {
          return payload.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T decode(byte[] data, Class<T> type) {
          return type.cast(new String(data, StandardCharsets.UTF_8));
        }
      };

  @Test
  void eventsAreStoredInTheTransactionAndRelayed() throws Exception {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:outbox;DB_CLOSE_DELAY=-1");
    assertTransactionalRelay(h2);
  }

  static void assertTransactionalRelay(DataSource dataSource) {
    List<String> published = new CopyOnWriteArrayList<>();
    EventPublisher publisher =
        new EventPublisher() {
          @Override
          public PublishResult publish(String destination, EventEnvelope event) {
            published.add(destination + ":" + event.id());
            return new PublishResult(event.id(), destination, null);
          }

          @Override
          public boolean supports(String destination) {
            return true;
          }
        };
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizOutboxAutoConfiguration.class, AxizOutboxJdbcAutoConfiguration.class))
        .withPropertyValues(
            "axiz.outbox.initialize-schema=ALWAYS",
            "axiz.outbox.relay.poll-interval=50ms",
            "axiz.outbox.relay.publish-budget=1s")
        .withBean(DataSource.class, () -> dataSource)
        .withBean(
            PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
        .withBean(EventPublisher.class, () -> publisher)
        .withBean(
            EventFactory.class,
            () ->
                new EventFactory(
                    "urn:axiz:test",
                    "pe.axiz.test",
                    CODEC,
                    IdGenerator.uuidV7(),
                    Clock.systemUTC()))
        .run(
            ctx -> {
              TransactionalEvents events = ctx.getBean(TransactionalEvents.class);
              assertThrows(
                  IllegalStateException.class,
                  () -> events.enqueue("payments", "e-0", "x.v1", "payment", "p-0", "x"));
              TransactionTemplate tx =
                  new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
              tx.executeWithoutResult(
                  s ->
                      events.enqueue(
                          "payments", "e-1", "payment-created.v1", "payment", "p-1", "hello"));
              tx.executeWithoutResult(
                  s -> {
                    events.enqueue(
                        "payments", "e-2", "payment-created.v1", "payment", "p-2", "rolled back");
                    s.setRollbackOnly();
                  });
              // La confirmación del broker precede a la marca en la base de datos y al commit de la
              // transacción. Se observan ambos resultados antes de afirmar que el relay asíncrono
              // completó.
              Awaitility.await()
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(
                      () -> {
                        assertEquals(List.of("payments:e-1"), published);
                        assertEquals(0, ctx.getBean(OutboxStore.class).countPending());
                      });
              assertTrue(ctx.getBean(OutboxRelayRunner.class).isRunning());
            });
  }
}
