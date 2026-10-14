package pe.axiz.payments.business.application.command;

import org.jspecify.annotations.Nullable;
import pe.axiz.framework.cqrs.AuditedCommand;
import pe.axiz.payments.business.domain.PaymentOrder;
import pe.axiz.payments.business.domain.PaymentStatus;

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
