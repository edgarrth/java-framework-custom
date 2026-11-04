package pe.axiz.payments.orders.application.command;

import org.jspecify.annotations.Nullable;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;

/**
 * Moves an order to its next status.
 *
 * @param orderId order
 * @param status target status
 * @param reason why (shown to the customer when rejected)
 */
public record ChangePaymentOrderStatusCommand(
    String orderId, PaymentStatus status, @Nullable String reason)
    implements AuditedCommand<PaymentOrder> {

  @Override
  public String auditAction() {
    return "change-status";
  }

  @Override
  public String auditResource() {
    return "payment-order";
  }

  @Override
  public String auditResourceId() {
    return orderId;
  }
}
