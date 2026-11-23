package pe.axiz.payments.history.adapter.in.messaging;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.extension.cqrs.spring.ReactiveCommandBus;
import pe.axiz.payments.history.application.command.RecordPaymentStatusChangedCommand;
import pe.axiz.payments.history.domain.PaymentStatus;
import pe.axiz.spring.messaging.ReactiveTypedEventHandler;

/**
 * Proyecta {@code payment-order.status-changed.v1}; los cambios más antiguos o repetidos se
 * ignoran.
 */
@Component
@HandlesEvent(PaymentOrderStatusChangedHandler.TYPE)
public class PaymentOrderStatusChangedHandler
    extends ReactiveTypedEventHandler<PaymentOrderStatusChangedHandler.PaymentOrderStatusChanged> {

  static final String TYPE = "pe.axiz.payments.payment-order.status-changed.v1";

  /**
   * Payload del evento.
   *
   * @param orderId orden
   * @param customerId cliente
   * @param previous estado previo
   * @param status estado nuevo
   * @param reason motivo, cuando se indica
   */
  public record PaymentOrderStatusChanged(
      String orderId, String customerId, String previous, String status, @Nullable String reason) {}

  private final ReactiveCommandBus commands;

  public PaymentOrderStatusChangedHandler(ReactiveCommandBus commands) {
    this.commands = commands;
  }

  @Override
  protected Publisher<Void> on(PaymentOrderStatusChanged e, EventEnvelope envelope) {
    return commands.send(
        new RecordPaymentStatusChangedCommand(
            e.orderId(),
            e.customerId(),
            PaymentStatus.valueOf(e.status()),
            e.reason(),
            envelope.time()));
  }
}
