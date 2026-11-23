package pe.axiz.payments.bankingcore.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Orden de débito o abono sobre una cuenta.
 *
 * @param accountId número de cuenta en el core
 * @param amount monto positivo
 * @param reference referencia de negocio, única por movimiento (el core deduplica por ella)
 */
public record MovementOrder(
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String accountId,
    Money amount,
    String reference,
    @Nullable String holderId) {

  /** Movimiento sin verificación del titular (abonos, reversiones). */
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

  /** Recurso de lock que serializa los movimientos de una misma cuenta. */
  public String lockKey() {
    return "account:" + accountId;
  }
}
