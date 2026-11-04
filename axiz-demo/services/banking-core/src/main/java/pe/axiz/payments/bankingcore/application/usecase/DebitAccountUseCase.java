package pe.axiz.payments.bankingcore.application.usecase;

import pe.axiz.core.audit.Auditable;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.payments.bankingcore.application.port.out.CoreAccounts;
import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/** Holds and debits funds of an account in the core, one movement per account at a time. */
@UseCase
public class DebitAccountUseCase
    implements pe.axiz.structure.hexagonal.UseCase<DebitAccountUseCase.Input, Movement> {

  /**
   * Input of the use case.
   *
   * @param order what to debit
   * @param idempotencyKey caller key, forwarded to the core
   */
  public record Input(MovementOrder order, String idempotencyKey) {}

  private final CoreAccounts core;
  private final LockTemplate locks;
  private final EventMetrics metrics;

  public DebitAccountUseCase(CoreAccounts core, LockTemplate locks, EventMetrics metrics) {
    this.core = core;
    this.locks = locks;
    this.metrics = metrics;
  }

  @Override
  @Auditable(action = "account.debit", resource = "account", captureArguments = false)
  public Movement execute(Input input) {
    Movement movement =
        AccountLocks.with(
            locks,
            input.order().lockKey(),
            () -> core.debit(input.order(), input.idempotencyKey()));
    metrics.record(
        MetricEvent.of("core.movement")
            .category("debit")
            .amount(input.order().amount())
            .outcome(Outcome.SUCCESS));
    return movement;
  }
}
