package pe.axiz.payments.business.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.money.Money;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.payments.business.domain.PaymentOrder;
import pe.axiz.payments.business.domain.PaymentStatus;
import pe.axiz.spring.outbox.TransactionalEvents;

/** The events the outbox receives: destination, type, aggregate and payload of each. */
class PaymentEventsOutboxAdapterTest {

  /**
   * Enqueued event as the adapter hands it over.
   *
   * @param destination destination
   * @param type type
   * @param aggregateType aggregate type
   * @param aggregateId aggregate id
   * @param payload payload
   */
  record Enqueued(
      String destination, String type, String aggregateType, String aggregateId, Object payload) {}

  /** Records instead of writing to the outbox table. */
  static final class Recording extends TransactionalEvents {
    final List<Enqueued> events = new ArrayList<>();

    Recording() {
      super(
          new EventFactory(
              "urn:test",
              "test",
              (PayloadCodec)
                  Proxy.newProxyInstance(
                      PayloadCodec.class.getClassLoader(),
                      new Class<?>[] {PayloadCodec.class},
                      (p, m, a) -> {
                        throw new UnsupportedOperationException();
                      }),
              IdGenerator.uuidV7(),
              Clock.systemUTC()),
          new OutboxWriter(
              (OutboxStore)
                  Proxy.newProxyInstance(
                      OutboxStore.class.getClassLoader(),
                      new Class<?>[] {OutboxStore.class},
                      (p, m, a) -> {
                        throw new UnsupportedOperationException();
                      }),
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
      events.add(new Enqueued(destination, type, aggregateType, aggregateId, payload));
      return null;
    }
  }

  final Recording outbox = new Recording();
  final PaymentEventsOutboxAdapter adapter = new PaymentEventsOutboxAdapter(outbox);

  static PaymentOrder order() {
    return PaymentOrder.register(
        "P-1",
        "OP20261009-00000001",
        "C-1",
        "1910012345678",
        "00219100123456789053",
        Money.of("150.75", "PEN"),
        null,
        LocalDate.of(2026, 10, 9),
        Instant.parse("2026-10-09T15:00:00Z"));
  }

  @Test
  void aCreatedOrderCarriesWhatThePaymentProcessNeeds() {
    adapter.created(order());

    Enqueued e = outbox.events.get(0);
    assertThat(e.destination()).isEqualTo("payments");
    assertThat(e.type()).isEqualTo("payment-order.created.v1");
    assertThat(e.aggregateType()).isEqualTo("payment-order");
    assertThat(e.aggregateId()).isEqualTo("P-1");
    assertThat(e.payload())
        .isEqualTo(
            new PaymentEventsOutboxAdapter.PaymentOrderCreated(
                "P-1",
                "OP20261009-00000001",
                "C-1",
                "1910012345678",
                "00219100123456789053",
                new BigDecimal("150.75"),
                "PEN",
                "2026-10-09"));
  }

  @Test
  void statusChangesCarryThePreviousStatusAndAnEmptyReasonWhenThereIsNone() {
    PaymentOrder order = order();
    order.moveTo(PaymentStatus.PROCESSING, null);
    adapter.statusChanged(order, PaymentStatus.RECEIVED);
    order.moveTo(PaymentStatus.REJECTED, "insufficient funds");
    adapter.statusChanged(order, PaymentStatus.PROCESSING);

    assertThat(outbox.events)
        .extracting(Enqueued::payload)
        .containsExactly(
            new PaymentEventsOutboxAdapter.PaymentOrderStatusChanged(
                "P-1", "C-1", "RECEIVED", "PROCESSING", ""),
            new PaymentEventsOutboxAdapter.PaymentOrderStatusChanged(
                "P-1", "C-1", "PROCESSING", "REJECTED", "insufficient funds"));
    assertThat(outbox.events)
        .extracting(Enqueued::type)
        .containsOnly("payment-order.status-changed.v1");
  }
}
