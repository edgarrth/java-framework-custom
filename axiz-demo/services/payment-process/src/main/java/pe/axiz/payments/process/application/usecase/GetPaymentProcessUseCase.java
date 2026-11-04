package pe.axiz.payments.process.application.usecase;

import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.payments.process.domain.PaymentProcess;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/** State of the payment process of an order. */
@UseCase
public class GetPaymentProcessUseCase
    implements pe.axiz.structure.hexagonal.UseCase<GetPaymentProcessUseCase.Input, PaymentProcess> {

  /**
   * Input of the use case.
   *
   * @param processId process id (the payment order id)
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
