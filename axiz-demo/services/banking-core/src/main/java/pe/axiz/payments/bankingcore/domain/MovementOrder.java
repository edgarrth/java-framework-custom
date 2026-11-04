package pe.axiz.payments.bankingcore.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * An order to debit or credit an account.
 *
 * @param accountId account number in the core
 * @param amount positive amount
 * @param reference business reference, unique per movement (the core de-duplicates by it)
 */
public record MovementOrder(
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String accountId,
    Money amount,
    String reference,
    @Nullable String holderId) {

  /** A movement without holder check (credits, reversals). */
  public MovementOrder(String accountId, Money amount, String reference) {
    this(accountId, amount, reference, null);
  }

  public MovementOrder {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(reference, "reference");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }

  /** Lock resource that serializes movements of the same account. */
  public String lockKey() {
    return "account:" + accountId;
  }
}
