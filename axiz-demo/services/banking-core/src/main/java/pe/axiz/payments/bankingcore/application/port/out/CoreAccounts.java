package pe.axiz.payments.bankingcore.application.port.out;

import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;

/**
 * Cuentas del sistema core bancario. Las implementaciones traducen los errores del core a {@code
 * IntegrationErrors}; cada llamada lleva la clave de idempotencia del invocador para que el core
 * nunca contabilice un movimiento dos veces.
 */
public interface CoreAccounts {

  Movement debit(MovementOrder order, String idempotencyKey);

  /** Revierte un débito; los movimientos desconocidos lanzan {@code NotFoundException}. */
  Movement reverse(String movementId, String idempotencyKey);

  Movement credit(MovementOrder order, String idempotencyKey);
}
