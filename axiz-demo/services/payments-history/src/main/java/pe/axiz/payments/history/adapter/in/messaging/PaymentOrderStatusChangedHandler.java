package pe.axiz.payments.history.adapter.in.messaging;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.framework.cqrs.spring.ReactiveCommandBus;
import pe.axiz.framework.eda.HandlesEvent;
import pe.axiz.framework.eda.ReactiveTypedEventHandler;
import pe.axiz.payments.history.application.command.RecordPaymentStatusChangedCommand;
import pe.axiz.payments.history.domain.PaymentStatus;

/** Projects {@code payment-order.status-changed.v1}; older or repeated changes are ignored. */
@Component
@HandlesEvent(PaymentOrderStatusChangedHandler.TYPE)
public class PaymentOrderStatusChangedHandler
    extends ReactiveTypedEventHandler<PaymentOrderStatusChangedHandler.PaymentOrderStatusChanged> {

  static final String TYPE = "pe.axiz.payments.payment-order.status-changed.v1";

  /**
   * Payload of the event.
   *
   * @param orderId order
   * @param customerId customer
   * @param previous previous status
   * @param status new status
   * @param reason reason, when given
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
