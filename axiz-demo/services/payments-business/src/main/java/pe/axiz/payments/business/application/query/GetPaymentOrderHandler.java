package pe.axiz.payments.business.application.query;

import org.springframework.stereotype.Component;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.framework.cqrs.QueryHandler;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.payments.business.domain.PaymentOrder;

/** Reads one order (read-only transaction). */
@Component
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
