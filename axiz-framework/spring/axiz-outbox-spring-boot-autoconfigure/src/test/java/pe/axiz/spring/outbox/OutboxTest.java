package pe.axiz.spring.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
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

class OutboxTest {

  static final PayloadCodec CODEC =
      new PayloadCodec() {
        @Override
        public String contentType() {
          return "text/plain";
        }

        @Override
        public byte[] encode(Object payload) {
          return payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public <T> T decode(byte[] data, Class<T> type) {
          return type.cast(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        }
      };

  @Test
  void eventsAreStoredInTheTransactionAndRelayed() throws Exception {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:outbox;DB_CLOSE_DELAY=-1");
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
        .withConfiguration(AutoConfigurations.of(AxizOutboxAutoConfiguration.class))
        .withPropertyValues("axiz.outbox.relay.poll-interval=50ms")
        .withBean(DataSource.class, () -> h2)
        .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2))
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
              // Broker acknowledgement precedes the database mark and transaction commit.
              // Observe both outcomes before asserting that the asynchronous relay completed.
              org.awaitility.Awaitility.await()
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
