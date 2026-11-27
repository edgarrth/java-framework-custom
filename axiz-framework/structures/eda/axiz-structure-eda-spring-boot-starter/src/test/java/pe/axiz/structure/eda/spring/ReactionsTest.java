package pe.axiz.structure.eda.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

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
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxWriter;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.spring.outbox.TransactionalEvents;
import reactor.core.publisher.Mono;

/**
 * Las reacciones encaminan cada evento emitido a su destino, y los handlers conservan los fallos
 * comprobados.
 */
class ReactionsTest {

  private static final EventEnvelope ENVELOPE =
      EventEnvelope.builder()
          .id("e-1")
          .source("urn:test")
          .type("pe.axiz.cards.card-blocked.v1")
          .time(Instant.EPOCH)
          .subject("c-1")
          .dataContentType("application/json")
          .data(new byte[0])
          .extensions(Map.of())
          .build();

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

  /** Registra adónde se envió cada evento. */
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

  /** Contraparte reactiva. */
  static final class RecordingReactiveEvents extends ReactiveTransactionalEvents {
    final List<String> sent = new CopyOnWriteArrayList<>();

    RecordingReactiveEvents() {
      super(
          factory(),
          new R2dbcOutboxWriter(
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
        .withConfiguration(
            AutoConfigurations.of(
                AxizEdaAutoConfiguration.class, AxizMessagingAutoConfiguration.class))
        .withPropertyValues(
            "axiz.messaging.routes.default-destination=cards-events",
            "axiz.messaging.routes.by-type[pe.axiz.cards.card-blocked.v1]=cards-alerts")
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
}
