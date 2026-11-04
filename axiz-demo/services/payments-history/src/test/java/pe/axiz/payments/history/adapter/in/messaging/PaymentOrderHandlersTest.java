package pe.axiz.payments.history.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.spring.ReactiveCommandBus;
import pe.axiz.payments.history.application.command.RecordPaymentRegisteredCommand;
import pe.axiz.payments.history.application.command.RecordPaymentStatusChangedCommand;
import pe.axiz.payments.history.domain.PaymentStatus;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;

@ExtendWith(BlockHoundExtension.class)
class PaymentOrderHandlersTest {

  private final List<Command<?>> sent = new ArrayList<>();
  private final ReactiveCommandBus bus =
      new ReactiveCommandBus() {
        @Override
        public <R> Mono<R> send(Command<R> command) {
          return Mono.fromRunnable(() -> sent.add(command));
        }
      };

  private static EventEnvelope envelope(String type, Instant time) {
    return new EventEnvelope(
        "evt-1",
        "urn:axiz:payments-business",
        type,
        time,
        "p-1",
        "application/json",
        "{}".getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  @Test
  void createdOrdersAreRecordedWithTheEventTime() throws Exception {
    PaymentOrderCreatedHandler handler = new PaymentOrderCreatedHandler(bus);
    assertThat(handler.eventType()).isEqualTo("pe.axiz.payments.payment-order.created.v1");
    Instant at = Instant.parse("2026-10-11T15:00:00Z");
    handler.handle(
        new PaymentOrderCreatedHandler.PaymentOrderCreated(
            "p-1",
            "OP20261011-00000001",
            "C-1001",
            "00219100123456789053",
            new BigDecimal("150.75"),
            "PEN",
            LocalDate.parse("2026-10-12")),
        envelope(handler.eventType(), at));
    assertThat(sent).hasSize(1);
    RecordPaymentRegisteredCommand c = (RecordPaymentRegisteredCommand) sent.getFirst();
    assertThat(c.paymentId()).isEqualTo("p-1");
    assertThat(c.occurredAt()).isEqualTo(at);
  }

  @Test
  void statusChangesAreRecorded() throws Exception {
    PaymentOrderStatusChangedHandler handler = new PaymentOrderStatusChangedHandler(bus);
    handler.handle(
        new PaymentOrderStatusChangedHandler.PaymentOrderStatusChanged(
            "p-1", "C-1001", "PROCESSING", "COMPLETED", null),
        envelope(handler.eventType(), Instant.EPOCH));
    RecordPaymentStatusChangedCommand c = (RecordPaymentStatusChangedCommand) sent.getFirst();
    assertThat(c.status()).isEqualTo(PaymentStatus.COMPLETED);
  }
}
