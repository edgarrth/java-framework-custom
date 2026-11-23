package pe.axiz.payments.process.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Lo que un proceso de pago debe ejecutar: debitar la cuenta del cliente y enviar la transferencia.
 *
 * @param orderId orden de pago (también el id del proceso: un proceso por orden)
 * @param operationNumber número de operación, la referencia que se muestra al cliente y al core
 * @param debtorAccount cuenta que se debita
 * @param creditorCci cuenta de destino
 * @param amount monto positivo
 * @param customerId titular de la cuenta deudora: el core rechaza el débito cuando la cuenta no le
 *     pertenece (null solo en procesos iniciados sin ese dato registrado)
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

  /**
   * Clave de idempotencia de una llamada aguas abajo de este proceso (estable entre reintentos).
   */
  public String keyFor(String step) {
    return "pp-" + orderId + "-" + step;
  }
}
