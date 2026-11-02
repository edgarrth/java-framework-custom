package pe.axiz.payments.history.application.command;

import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.domain.PaymentHistory;
import reactor.core.publisher.Mono;

/** Projects a status change (stale changes are ignored by the domain). */
public class RecordPaymentStatusChangedHandler
    implements ReactiveCommandHandler<RecordPaymentStatusChangedCommand, Void> {

  private final PaymentHistories histories;

  public RecordPaymentStatusChangedHandler(PaymentHistories histories) {
    this.histories = histories;
  }

  @Override
  public Mono<Void> handle(RecordPaymentStatusChangedCommand c) {
    return histories
        .apply(
            c.paymentId(),
            current ->
                PaymentHistory.statusChanged(
                    current, c.paymentId(), c.customerId(), c.status(), c.reason(), c.occurredAt()))
        .then();
  }
}
