package pe.axiz.payments.orders.application.command;

import org.jspecify.annotations.Nullable;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;

/**
 * Mueve una orden a su siguiente estado.
 *
 * @param orderId orden
 * @param status estado de destino
 * @param reason motivo (se muestra al cliente cuando se rechaza)
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
