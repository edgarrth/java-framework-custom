package pe.axiz.payments.process.application.saga;

import org.jspecify.annotations.Nullable;
import pe.axiz.payments.process.domain.PaymentInstruction;

/**
 * Contexto de la saga de pago, persistido tras cada paso.
 *
 * @param instruction qué se paga
 * @param movementId movimiento de débito, una vez debitado
 * @param transferId transferencia en la red, una vez enviada
 * @param completionPending el dinero se movió, pero todavía no se pudo informar a payment-orders
 *     (la conciliación de liquidación completa la orden)
 */
public record PaymentContext(
    PaymentInstruction instruction,
    @Nullable String movementId,
    @Nullable String transferId,
    boolean completionPending) {

  public static PaymentContext start(PaymentInstruction instruction) {
    return new PaymentContext(instruction, null, null, false);
  }

  PaymentContext debited(String movement) {
    return new PaymentContext(instruction, movement, transferId, completionPending);
  }

  PaymentContext sent(String transfer) {
    return new PaymentContext(instruction, movementId, transfer, completionPending);
  }

  PaymentContext pendingCompletion() {
    return new PaymentContext(instruction, movementId, transferId, true);
  }
}
