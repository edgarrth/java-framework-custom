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
 * {@link PaymentEvents} through the transactional outbox: the event row commits with the order and
 * the relay publishes it to MSK (destination {@code payments}). Payloads carry no account numbers.
 */
@Component
public class PaymentEventsOutboxAdapter implements PaymentEvents {

  static final String DESTINATION = "payments";
  static final String AGGREGATE = "payment-order";

  /**
   * Payload of {@code payment-order.created.v1}. It carries the accounts the payment process needs:
   * the {@code payments} topic is internal (MSK with TLS and IAM authorization per consumer) and
   * the account fields are sensitive, so they are masked in every log and audit record.
   *
   * @param orderId order
   * @param operationNumber operation number
   * @param customerId customer
   * @param debtorAccount account to debit
   * @param creditorCci destination account
   * @param amount amount
   * @param currency currency
   * @param processingDate processing date (ISO)
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
   * Payload of {@code payment-order.status-changed.v1}.
   *
   * @param orderId order
   * @param customerId customer
   * @param previous previous status
   * @param status new status
   * @param reason reason of the change
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
