package pe.axiz.payments.process.application.usecase;

import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.payments.process.domain.PaymentProcess;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/** Estado del proceso de pago de una orden. */
@UseCase
public class GetPaymentProcessUseCase
    implements pe.axiz.structure.hexagonal.UseCase<GetPaymentProcessUseCase.Input, PaymentProcess> {

  /**
   * Entrada del caso de uso.
   *
   * @param processId id del proceso (el id de la orden de pago)
   */
  public record Input(String processId) {}

  private final SagaStateStore store;

  public GetPaymentProcessUseCase(SagaStateStore store) {
    this.store = store;
  }

  @Override
  public PaymentProcess execute(Input input) {
    return store
        .find(input.processId())
        .map(ProcessViews::of)
        .orElseThrow(() -> new NotFoundException("payment-process", input.processId()));
  }
}
