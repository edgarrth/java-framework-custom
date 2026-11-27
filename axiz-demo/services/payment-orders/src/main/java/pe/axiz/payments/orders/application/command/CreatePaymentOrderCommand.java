package pe.axiz.payments.orders.application.command;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * Registra una orden de pago.
 *
 * @param customerId cliente
 * @param debtorAccount cuenta que se debita
 * @param creditorCci cuenta interbancaria (CCI) que se abona
 * @param amount monto
 * @param currency código ISO 4217
 * @param description texto libre para el acreedor
 * @param requestKey la clave del cliente para esta solicitud (su Idempotency-Key): única por
 *     cliente, de modo que una solicitud reintentada devuelve la orden ya registrada en lugar de
 *     crear otra
 */
public record CreatePaymentOrderCommand(
    String customerId,
    String debtorAccount,
    String creditorCci,
    BigDecimal amount,
    String currency,
    @Nullable String description,
    @Nullable String requestKey)
    implements AuditedCommand<PaymentOrder> {

  @Override
  public String auditAction() {
    return "create";
  }

  @Override
  public String auditResource() {
    return "payment-order";
  }
}
