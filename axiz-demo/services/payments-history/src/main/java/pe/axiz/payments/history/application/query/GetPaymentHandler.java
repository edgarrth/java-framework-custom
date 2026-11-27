package pe.axiz.payments.history.application.query;

import pe.axiz.core.errors.NotFoundException;
import pe.axiz.extension.cqrs.ReactiveQueryHandler;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.domain.PaymentHistory;
import reactor.core.publisher.Mono;

/**
 * Lee un pago (transacción reactiva de solo lectura); responde 404 hasta que se proyecta su primer
 * evento.
 */
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
