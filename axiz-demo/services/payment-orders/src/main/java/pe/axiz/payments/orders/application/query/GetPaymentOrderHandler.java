package pe.axiz.payments.orders.application.query;

import pe.axiz.core.errors.NotFoundException;
import pe.axiz.extension.cqrs.QueryHandler;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.domain.PaymentOrder;

/** Reads one order (read-only transaction). */
public class GetPaymentOrderHandler implements QueryHandler<GetPaymentOrderQuery, PaymentOrder> {

  private final PaymentOrders orders;

  public GetPaymentOrderHandler(PaymentOrders orders) {
    this.orders = orders;
  }

  @Override
  public PaymentOrder handle(GetPaymentOrderQuery query) {
    return orders
        .find(query.orderId())
        .orElseThrow(() -> new NotFoundException("payment-order", query.orderId()));
  }
}
