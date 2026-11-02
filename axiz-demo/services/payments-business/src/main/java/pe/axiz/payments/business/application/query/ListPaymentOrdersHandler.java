package pe.axiz.payments.business.application.query;

import pe.axiz.core.commons.Page;
import pe.axiz.extension.cqrs.QueryHandler;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.payments.business.domain.PaymentOrder;

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
