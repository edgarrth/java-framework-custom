package pe.axiz.payments.orders.adapter.out.messaging;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import pe.axiz.payments.orders.application.port.out.PaymentEvents;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;
import pe.axiz.spring.outbox.TransactionalEvents;

/**
 * {@link PaymentEvents} mediante el outbox transaccional: la fila del evento se confirma junto con
 * la orden y el relay la publica en MSK (destino {@code payments}). Los payloads no llevan números
 * de cuenta.
 */
@Component
public class PaymentEventsOutboxAdapter implements PaymentEvents {

  static final String DESTINATION = "payments";
  static final String AGGREGATE = "payment-order";

  /**
   * Payload de {@code payment-order.created.v1}. Lleva las cuentas que necesita el proceso de
   * pagos: el tópico {@code payments} es interno (MSK con TLS y autorización IAM por consumidor) y
   * los campos de cuenta son sensibles, por lo que se enmascaran en cada log y registro de
   * auditoría.
   *
   * @param orderId orden
   * @param operationNumber número de operación
   * @param customerId cliente
   * @param debtorAccount cuenta que se debita
   * @param creditorCci cuenta de destino
   * @param amount monto
   * @param currency moneda
   * @param processingDate fecha de procesamiento (ISO)
   */
  public record PaymentOrderCreated(
      String orderId,
      String operationNumber,
      String customerId,
      @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String debtorAccount,
      @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
      BigDecimal amount,
      String currency,
      String processingDate) {}

  /**
   * Payload de {@code payment-order.status-changed.v1}.
   *
   * @param orderId orden
   * @param customerId cliente
   * @param previous estado previo
   * @param status estado nuevo
   * @param reason motivo del cambio
   */
  public record PaymentOrderStatusChanged(
      String orderId, String customerId, String previous, String status, String reason) {}

  private final TransactionalEvents events;

  public PaymentEventsOutboxAdapter(TransactionalEvents events) {
    this.events = events;
  }

  @Override
  public void created(PaymentOrder order) {
    events.enqueue(
        DESTINATION,
        null,
        "payment-order.created.v1",
        AGGREGATE,
        order.id(),
        new PaymentOrderCreated(
            order.id(),
            order.operationNumber(),
            order.customerId(),
            order.debtorAccount(),
            order.creditorCci(),
            order.amount().amount(),
            order.amount().currencyCode(),
            order.processingDate().toString()));
  }

  @Override
  public void statusChanged(PaymentOrder order, PaymentStatus previous) {
    String reason = order.statusReason();
    events.enqueue(
        DESTINATION,
        null,
        "payment-order.status-changed.v1",
        AGGREGATE,
        order.id(),
        new PaymentOrderStatusChanged(
            order.id(),
            order.customerId(),
            previous.name(),
            order.status().name(),
            reason == null ? "" : reason));
  }
}
