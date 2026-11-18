package pe.axiz.payments.orders.application.query;

import pe.axiz.core.api.Page;
import pe.axiz.extension.cqrs.QueryHandler;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.domain.PaymentOrder;

/** Lists the orders of a customer. */
public class ListPaymentOrdersHandler
    implements QueryHandler<ListPaymentOrdersQuery, Page<PaymentOrder>> {

  private final PaymentOrders orders;

  public ListPaymentOrdersHandler(PaymentOrders orders) {
    this.orders = orders;
  }

  @Override
  public Page<PaymentOrder> handle(ListPaymentOrdersQuery query) {
    return orders.byCustomer(query.customerId(), query.page());
  }
}
