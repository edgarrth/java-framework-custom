package pe.axiz.payments.business.application.access;

import pe.axiz.core.errors.NotFoundException;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.payments.business.application.query.GetPaymentOrderQuery;
import pe.axiz.payments.business.domain.PaymentOrder;

/**
 * An order is visible to its customer and to services that may read orders. Another customer's
 * order is reported as not found, so ids cannot be probed.
 */
public class GetPaymentOrderAccess implements AccessPolicy<GetPaymentOrderQuery> {

  private final PaymentOrders orders;

  public GetPaymentOrderAccess(PaymentOrders orders) {
    this.orders = orders;
  }

  @Override
  public Class<GetPaymentOrderQuery> requestType() {
    return GetPaymentOrderQuery.class;
  }

  @Override
  public void check(GetPaymentOrderQuery request, Caller caller) {
    String owner = orders.find(request.orderId()).map(PaymentOrder::customerId).orElse(null);
    if (owner == null || !PaymentAuthorities.canRead(caller, owner)) {
      throw new NotFoundException("payment-order", request.orderId());
    }
  }
}
