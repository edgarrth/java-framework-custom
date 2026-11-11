package pe.axiz.payments.process.application.saga;

import org.jspecify.annotations.Nullable;
import pe.axiz.payments.process.domain.PaymentInstruction;

/**
 * Context of the payment saga, persisted after every step.
 *
 * @param instruction what to pay
 * @param movementId debit movement, once debited
 * @param transferId network transfer, once sent
 * @param completionPending the money moved but payment-orders could not be told yet (the settlement
 *     reconciliation completes the order)
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
