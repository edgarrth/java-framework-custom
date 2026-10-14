package pe.axiz.payments.coreintegration.application.usecase;

import pe.axiz.core.audit.Auditable;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.framework.hexagonal.annotation.UseCase;
import pe.axiz.payments.coreintegration.application.port.out.CoreAccounts;
import pe.axiz.payments.coreintegration.domain.Movement;
import pe.axiz.payments.coreintegration.domain.MovementOrder;

/** Credits an account of the core (the core exposes credits only through SOAP). */
@UseCase
public class CreditAccountUseCase
    implements pe.axiz.framework.hexagonal.UseCase<CreditAccountUseCase.Input, Movement> {

  /**
   * Input of the use case.
   *
   * @param order what to credit
   * @param idempotencyKey caller key, forwarded to the core
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
