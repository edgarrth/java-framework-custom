package pe.axiz.payments.history.application.query;

import org.springframework.stereotype.Component;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.framework.cqrs.ReactiveQueryHandler;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.domain.PaymentHistory;
import reactor.core.publisher.Mono;

/** Reads one payment (read-only reactive transaction); 404 until its first event is projected. */
@Component
public class GetPaymentHandler implements ReactiveQueryHandler<GetPaymentQuery, PaymentHistory> {

  private final PaymentHistories histories;

  public GetPaymentHandler(PaymentHistories histories) {
    this.histories = histories;
  }

  @Override
  public Mono<PaymentHistory> handle(GetPaymentQuery query) {
    return histories
        .find(query.paymentId())
        .switchIfEmpty(Mono.error(() -> new NotFoundException("payment", query.paymentId())));
  }
}
