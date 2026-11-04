package pe.axiz.payments.bankingcore.application.port.out;

import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;

/**
 * Accounts of the core banking system. Implementations translate core errors to {@code
 * IntegrationErrors}; every call carries the caller's idempotency key so the core never posts a
 * movement twice.
 */
public interface CoreAccounts {

  Movement debit(MovementOrder order, String idempotencyKey);

  /** Reverses a debit; unknown movements raise {@code NotFoundException}. */
  Movement reverse(String movementId, String idempotencyKey);

  Movement credit(MovementOrder order, String idempotencyKey);
}
