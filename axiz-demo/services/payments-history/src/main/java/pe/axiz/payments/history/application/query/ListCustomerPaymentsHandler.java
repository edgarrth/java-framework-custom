package pe.axiz.payments.history.application.query;

import pe.axiz.core.api.Page;
import pe.axiz.extension.cqrs.ReactiveQueryHandler;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.domain.PaymentHistory;
import reactor.core.publisher.Mono;

/** Lista los pagos de un cliente. */
public class ListCustomerPaymentsHandler
    implements ReactiveQueryHandler<ListCustomerPaymentsQuery, Page<PaymentHistory>> {

  private final PaymentHistories histories;

  public ListCustomerPaymentsHandler(PaymentHistories histories) {
    this.histories = histories;
  }

  @Override
  public Mono<Page<PaymentHistory>> handle(ListCustomerPaymentsQuery query) {
    return histories.byCustomer(query.customerId(), query.status(), query.page());
  }
}
