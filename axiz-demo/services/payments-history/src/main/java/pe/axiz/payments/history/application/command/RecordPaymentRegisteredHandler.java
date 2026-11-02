package pe.axiz.payments.history.application.command;

import pe.axiz.core.money.Money;
import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.domain.PaymentHistory;
import reactor.core.publisher.Mono;

/** Projects a registered order; runs in the reactive transaction of the command pipeline. */
public class RecordPaymentRegisteredHandler
    implements ReactiveCommandHandler<RecordPaymentRegisteredCommand, Void> {

  private final PaymentHistories histories;

  public RecordPaymentRegisteredHandler(PaymentHistories histories) {
    this.histories = histories;
  }

  @Override
  public Mono<Void> handle(RecordPaymentRegisteredCommand c) {
    PaymentHistory.Registration registration =
        new PaymentHistory.Registration(
            c.operationNumber(),
            c.creditorCci(),
            Money.ofExact(c.amount(), c.currency()),
            c.processingDate());
    return histories
        .apply(
            c.paymentId(),
            current ->
                PaymentHistory.registered(
                    current, c.paymentId(), c.customerId(), registration, c.occurredAt()))
        .then();
  }
}
