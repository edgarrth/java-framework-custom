package pe.axiz.payments.coreintegration.application.usecase;

import pe.axiz.core.audit.Auditable;
import pe.axiz.framework.hexagonal.annotation.UseCase;
import pe.axiz.payments.coreintegration.application.port.out.CoreAccounts;
import pe.axiz.payments.coreintegration.domain.Movement;

/**
 * Reverses a debit: the compensation of the payment saga. Repeating it with the same key is safe.
 */
@UseCase
public class ReverseDebitUseCase
    implements pe.axiz.framework.hexagonal.UseCase<ReverseDebitUseCase.Input, Movement> {

  /**
   * Input of the use case.
   *
   * @param movementId debit to reverse
   * @param idempotencyKey caller key, forwarded to the core
   */
  public record Input(String movementId, String idempotencyKey) {}

  private final CoreAccounts core;

  public ReverseDebitUseCase(CoreAccounts core) {
    this.core = core;
  }

  @Override
  @Auditable(action = "account.debit.reverse", resource = "movement", captureArguments = false)
  public Movement execute(Input input) {
    return core.reverse(input.movementId(), input.idempotencyKey());
  }
}
