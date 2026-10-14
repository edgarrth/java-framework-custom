package pe.axiz.payments.process.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * What a payment process has to execute: debit the customer's account and send the transfer.
 *
 * @param orderId payment order (also the process id: one process per order)
 * @param operationNumber operation number, the reference shown to the customer and the core
 * @param debtorAccount account to debit
 * @param creditorCci destination account
 * @param amount positive amount
 * @param customerId holder of the debtor account: the core rejects the debit when the account is
 *     not theirs (null only for processes started before it was recorded)
 */
public record PaymentInstruction(
    String orderId,
    String operationNumber,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String debtorAccount,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
    Money amount,
    @Nullable String customerId) {

  public PaymentInstruction {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(operationNumber, "operationNumber");
    Objects.requireNonNull(debtorAccount, "debtorAccount");
    Objects.requireNonNull(creditorCci, "creditorCci");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }

  /** Idempotency key of one downstream call of this process (stable across retries). */
  public String keyFor(String step) {
    return "pp-" + orderId + "-" + step;
  }
}
