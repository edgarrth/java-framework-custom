package pe.axiz.payments.orders.application.access;

import pe.axiz.core.errors.NotFoundException;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.application.query.GetPaymentOrderQuery;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * Una orden es visible para su cliente y para los servicios que pueden leer órdenes. La orden de
 * otro cliente se informa como no encontrada, de modo que no se pueden sondear ids.
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
