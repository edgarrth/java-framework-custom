package pe.axiz.payments.orders.application.command;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * Registers a payment order.
 *
 * @param customerId customer
 * @param debtorAccount account to debit
 * @param creditorCci inter-bank account (CCI) to credit
 * @param amount amount
 * @param currency ISO 4217 code
 * @param description free text for the creditor
 * @param requestKey the client's key of this request (its Idempotency-Key): unique per customer, so
 *     a retried request returns the order already registered instead of creating another one
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
