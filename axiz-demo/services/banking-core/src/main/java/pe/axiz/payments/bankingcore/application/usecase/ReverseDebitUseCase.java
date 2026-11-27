package pe.axiz.payments.bankingcore.application.usecase;

import pe.axiz.core.audit.Auditable;
import pe.axiz.payments.bankingcore.application.port.out.CoreAccounts;
import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/**
 * Revierte un débito: la compensación de la saga de pagos. Repetirlo con la misma clave es seguro.
 */
@UseCase
public class ReverseDebitUseCase
    implements pe.axiz.structure.hexagonal.UseCase<ReverseDebitUseCase.Input, Movement> {

  /**
   * Entrada del caso de uso.
   *
   * @param movementId débito que se revierte
   * @param idempotencyKey clave del invocador, reenviada al core
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
