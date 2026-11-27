package pe.axiz.payments.bankingcore.application.usecase;

import pe.axiz.core.audit.Auditable;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.payments.bankingcore.application.port.out.CoreAccounts;
import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/** Abona a una cuenta del core (el core expone los abonos solo mediante SOAP). */
@UseCase
public class CreditAccountUseCase
    implements pe.axiz.structure.hexagonal.UseCase<CreditAccountUseCase.Input, Movement> {

  /**
   * Entrada del caso de uso.
   *
   * @param order qué se abona
   * @param idempotencyKey clave del invocador, reenviada al core
   */
  public record Input(MovementOrder order, String idempotencyKey) {}

  private final CoreAccounts core;
  private final EventMetrics metrics;

  public CreditAccountUseCase(CoreAccounts core, EventMetrics metrics) {
    this.core = core;
    this.metrics = metrics;
  }

  @Override
  @Auditable(action = "account.credit", resource = "account", captureArguments = false)
  public Movement execute(Input input) {
    Movement movement = core.credit(input.order(), input.idempotencyKey());
    metrics.record(
        MetricEvent.of("core.movement")
            .category("credit")
            .amount(input.order().amount())
            .outcome(Outcome.SUCCESS));
    return movement;
  }
}
