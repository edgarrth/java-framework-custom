package pe.axiz.framework.eda.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.core.outbox.r2dbc.ReactiveOutboxWriter;
import pe.axiz.core.persistence.r2dbc.ReactiveSql;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.spring.outbox.TransactionalEvents;
import reactor.core.publisher.Mono;

/** Reactions route each emitted event to its destination, and handlers keep checked failures. */
class ReactionsTest {

  private static final EventEnvelope ENVELOPE =
      new EventEnvelope(
          "e-1",
          "urn:test",
          "pe.axiz.cards.card-blocked.v1",
          Instant.EPOCH,
          "c-1",
          "application/json",
          new byte[0],
          Map.of());

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

  private static EventFactory factory() {
    return new EventFactory("urn:test", "", CODEC, IdGenerator.uuidV7(), Clock.systemUTC());
  }

  @SuppressWarnings("unchecked")
  private static <T> T nothing(Class<T> type) {
    return (T)
        Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> null);
  }

  /** Records where each event was sent. */
  static final class RecordingEvents extends TransactionalEvents {
    final List<String> sent = new CopyOnWriteArrayList<>();

    RecordingEvents() {
      super(factory(), new OutboxWriter(nothing(OutboxStore.class), Clock.systemUTC()));
    }

    @Override
    public EventEnvelope enqueue(
        String destination,
        @Nullable String eventId,
        String type,
        String aggregateType,
        @Nullable String aggregateId,
        Object payload) {
      sent.add(destination + "|" + type + "|" + aggregateType + "|" + aggregateId + "|" + eventId);
      return ENVELOPE;
    }
  }

  /** The reactive counterpart. */
  static final class RecordingReactiveEvents extends ReactiveTransactionalEvents {
    final List<String> sent = new CopyOnWriteArrayList<>();

    RecordingReactiveEvents() {
      super(
          factory(),
          new ReactiveOutboxWriter(
              new R2dbcOutboxStore(
                  nothing(ReactiveSql.class), "axiz_outbox", Duration.ofSeconds(1)),
              Clock.systemUTC()));
    }

    @Override
    public Mono<EventEnvelope> enqueue(
        String destination,
        @Nullable String eventId,
        String type,
        String aggregateType,
        @Nullable String aggregateId,
        Object payload) {
      return Mono.fromCallable(
          () -> {
            sent.add(destination + "|" + type);
            return ENVELOPE;
          });
    }
  }

  @Test
  void eventsGoToTheirRouteOrTheDefaultDestination() {
    RecordingEvents events = new RecordingEvents();
    RecordingReactiveEvents reactive = new RecordingReactiveEvents();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizEdaAutoConfiguration.class))
        .withPropertyValues(
            "axiz.eda.destination=cards-events",
            "axiz.eda.routes[pe.axiz.cards.card-blocked.v1]=cards-alerts")
        .withBean(TransactionalEvents.class, () -> events)
        .withBean(ReactiveTransactionalEvents.class, () -> reactive)
        .run(
            ctx -> {
              Reactions reactions = ctx.getBean(Reactions.class);
              assertSame(
                  ENVELOPE,
                  reactions.emit(
                      "pe.axiz.cards.card-blocked.v1", "card", "c-1", "blocked", "evt-1"));
              reactions.emit("pe.axiz.cards.card-issued.v1", "card", null, "issued", "evt-2");
              assertThat(events.sent)
                  .containsExactly(
                      "cards-alerts|pe.axiz.cards.card-blocked.v1|card|c-1|evt-1",
                      "cards-events|pe.axiz.cards.card-issued.v1|card|null|evt-2");

              ReactiveReactions reactiveReactions = ctx.getBean(ReactiveReactions.class);
              assertSame(
                  ENVELOPE,
                  reactiveReactions
                      .emit("pe.axiz.cards.card-issued.v1", "card", "c-2", "issued", "evt-3")
                      .block(Duration.ofSeconds(5)));
              assertThat(reactive.sent)
                  .containsExactly("cards-events|pe.axiz.cards.card-issued.v1");
            });
  }

  /** A handler that fails with a checked exception. */
  static final class Failing implements EventHandler<String> {
    @Override
    public String eventType() {
      return "pe.axiz.cards.card-blocked.v1";
    }

    @Override
    public Class<String> payloadType() {
      return String.class;
    }

    @Override
    public void handle(String payload, EventEnvelope envelope) throws Exception {
      if ("io".equals(payload)) {
        throw new IOException("statement store unreachable");
      }
      throw new IllegalStateException("bad state");
    }
  }

  @Test
  void aCheckedFailureLeavesTheTransactionAsItself() {
    Failing delegate = new Failing();
    TransactionalEventHandler<String> handler =
        new TransactionalEventHandler<>(delegate, new TransactionTemplate(new EdaTest.FakeTx()));
    assertSame(delegate, handler.delegate());
    assertEquals(String.class, handler.payloadType());
    assertEquals("pe.axiz.cards.card-blocked.v1", handler.eventType());
    IOException io = assertThrows(IOException.class, () -> handler.handle("io", ENVELOPE));
    assertEquals("statement store unreachable", io.getMessage());
    assertThrows(IllegalStateException.class, () -> handler.handle("state", ENVELOPE));
  }
}
