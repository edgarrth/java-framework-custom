package pe.axiz.payments.networkgateway.domain;

import java.util.Objects;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Transferencia interbancaria por enviar.
 *
 * @param creditorCci cuenta de destino (CCI)
 * @param amount monto positivo
 * @param reference referencia de extremo a extremo
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
