package pe.axiz.payments.orders.application.access;

import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.payments.orders.application.query.ListPaymentOrdersQuery;

/** Los clientes listan sus propias órdenes; los servicios necesitan {@code payments:read}. */
public class ListPaymentOrdersAccess implements AccessPolicy<ListPaymentOrdersQuery> {

  @Override
  public Class<ListPaymentOrdersQuery> requestType() {
    return ListPaymentOrdersQuery.class;
  }

  @Override
  public void check(ListPaymentOrdersQuery request, Caller caller) {
    if (!PaymentAuthorities.canRead(caller, request.customerId())) {
      throw new AccessException(CoreErrors.FORBIDDEN, "orders of another customer");
    }
  }
}
