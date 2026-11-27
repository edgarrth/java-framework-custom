package pe.axiz.structure.hexagonal.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.spring.outbox.TransactionalEvents;
import pe.axiz.structure.hexagonal.AggregateEvent;
import pe.axiz.structure.hexagonal.DomainEventSink;
import pe.axiz.structure.hexagonal.DomainEvents;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/**
 * Casos de uso en una transacción, sus eventos de dominio mediante el outbox y los fallos
 * comprobados.
 */
class UseCaseWiringTest {

  record CardBlocked(String eventId, Instant occurredAt, String aggregateId)
      implements AggregateEvent {
    @Override
    public String aggregateType() {
      return "card";
    }
  }

  record LimitRaised(String eventId, Instant occurredAt, String aggregateId)
      implements DomainEvent {}

  @UseCase("block-card")
  public static class BlockCard {
    public String execute(String cardId) {
      DomainEvents.raise(
          List.of(
              new CardBlocked("e-" + cardId, Instant.EPOCH, cardId),
              new LimitRaised("l-" + cardId, Instant.EPOCH, cardId)));
      return cardId;
    }
  }

  @UseCase(readOnly = true)
  public static class StatementLookup {
    public String find(String account) throws IOException {
      if (account.isBlank()) {
        throw new IOException("statement store unreachable");
      }
      return "statement-" + account;
    }
  }

  /** Registra si cada transacción fue de solo lectura. */
  static final class RecordingTransactions implements PlatformTransactionManager {
    final List<String> begun = new CopyOnWriteArrayList<>();

    @Override
    public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
      begun.add(definition != null && definition.isReadOnly() ? "read" : "write");
      return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }

  private static final PayloadCodec CODEC =
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

  /** Registra cada evento que el caso de uso deja en el outbox. */
  static final class RecordingEvents extends TransactionalEvents {
    final List<String> enqueued = new CopyOnWriteArrayList<>();

    RecordingEvents() {
      super(
          new EventFactory("urn:test", "", CODEC, IdGenerator.uuidV7(), Clock.systemUTC()),
          new OutboxWriter(
              (OutboxStore)
                  Proxy.newProxyInstance(
                      OutboxStore.class.getClassLoader(),
                      new Class<?>[] {OutboxStore.class},
                      (p, m, a) -> null),
              Clock.systemUTC()));
    }

    @Override
    public EventEnvelope enqueue(
        String destination,
        @Nullable String eventId,
        String type,
        String aggregateType,
        @Nullable String aggregateId,
        Object payload) {
      enqueued.add(destination + "|" + type + "|" + aggregateType + "|" + aggregateId);
      return EventEnvelope.builder()
          .id(eventId == null ? "x" : eventId)
          .source("urn:test")
          .type(type)
          .time(Instant.EPOCH)
          .subject(aggregateId)
          .dataContentType("text/plain")
          .data(new byte[0])
          .extensions(Map.of())
          .build();
    }
  }

  @Test
  void domainEventsGoThroughTheOutboxInsideTheTransactionOfTheUseCase() {
    RecordingTransactions transactions = new RecordingTransactions();
    RecordingEvents events = new RecordingEvents();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, AxizHexagonalAutoConfiguration.class))
        .withPropertyValues(
            "axiz.hexagonal.events.destination=cards-events",
            "axiz.hexagonal.events.destinations.card-blocked=cards-alerts")
        .withBean(PlatformTransactionManager.class, () -> transactions)
        .withBean(TransactionalEvents.class, () -> events)
        .withBean(BlockCard.class)
        .withBean(StatementLookup.class)
        .run(
            ctx -> {
              assertThat(ctx.getBean(DomainEventSink.class))
                  .isInstanceOf(OutboxDomainEventSink.class);
              assertEquals("c-1", ctx.getBean(BlockCard.class).execute("c-1"));
              assertThat(events.enqueued)
                  .containsExactly(
                      "cards-alerts|card-blocked.v1|card|c-1",
                      "cards-events|limit-raised.v1|aggregate|c-1");

              StatementLookup lookup = ctx.getBean(StatementLookup.class);
              assertEquals("statement-a-1", lookup.find("a-1"));
              IOException checked = assertThrows(IOException.class, () -> lookup.find(" "));
              assertEquals("statement store unreachable", checked.getMessage());
              assertThat(transactions.begun).containsExactly("write", "read", "read");
            });
  }

  @Test
  void theSpringTransactionHonoursTheReadOnlyFlag() {
    RecordingTransactions transactions = new RecordingTransactions();
    SpringUseCaseTransaction tx = new SpringUseCaseTransaction(transactions);
    assertEquals("w", tx.execute(false, () -> "w"));
    assertEquals("r", tx.execute(true, () -> "r"));
    assertThat(transactions.begun).containsExactly("write", "read");
  }
}
