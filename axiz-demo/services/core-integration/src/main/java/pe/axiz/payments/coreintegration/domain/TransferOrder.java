package pe.axiz.payments.coreintegration.domain;

import java.util.Objects;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * An interbank transfer to send.
 *
 * @param creditorCci destination account (CCI)
 * @param amount positive amount
 * @param reference end-to-end reference
 */
public record TransferOrder(
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
    Money amount,
    String reference) {

  public TransferOrder {
    Objects.requireNonNull(creditorCci, "creditorCci");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(reference, "reference");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }
}
