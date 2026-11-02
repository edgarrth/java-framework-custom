package pe.axiz.payments.process.application.usecase;

import java.util.Locale;
import java.util.Optional;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.saga.ContextCodec;
import pe.axiz.core.saga.SagaDefinition;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaResult;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.structure.hexagonal.annotation.UseCase;
import pe.axiz.payments.process.application.saga.PaymentContext;
import pe.axiz.payments.process.domain.PaymentInstruction;
import pe.axiz.payments.process.domain.PaymentProcess;

/**
 * Starts (or reports) the payment process of an order. One process per order: starting it again, as
 * a redelivered event or a retried request does, returns the existing process.
 */
@UseCase
public class StartPaymentProcessUseCase
    implements pe.axiz.structure.hexagonal.UseCase<
        StartPaymentProcessUseCase.Input, PaymentProcess> {

  /**
   * Input of the use case.
   *
   * @param instruction what to pay
   */
  public record Input(PaymentInstruction instruction) {}

  private final SagaOrchestrator orchestrator;
  private final SagaStateStore store;
  private final SagaDefinition<PaymentContext> saga;
  private final ContextCodec<PaymentContext> codec;
  private final EventMetrics metrics;

  public StartPaymentProcessUseCase(
      SagaOrchestrator orchestrator,
      SagaStateStore store,
      SagaDefinition<PaymentContext> paymentSaga,
      ContextCodec<PaymentContext> paymentContextCodec,
      EventMetrics metrics) {
    this.orchestrator = orchestrator;
    this.store = store;
    this.saga = paymentSaga;
    this.codec = paymentContextCodec;
    this.metrics = metrics;
  }

  @Override
  public PaymentProcess execute(Input input) {
    String id = input.instruction().orderId();
    Optional<SagaState> existing = store.find(id);
    if (existing.isPresent()) {
      return ProcessViews.of(existing.get());
    }
    SagaResult<PaymentContext> result =
        orchestrator.start(saga, codec, id, PaymentContext.start(input.instruction()));
    PaymentProcess process =
        new PaymentProcess(id, ProcessViews.status(result.status()), result.failedStep());
    metrics.record(
        MetricEvent.of("payment.process")
            .amount(input.instruction().amount())
            .outcome(result.completed() ? Outcome.SUCCESS : Outcome.REJECTED)
            .stage(process.status().name().toLowerCase(Locale.ROOT))
            .reason(result.failedStep()));
    return process;
  }
}
