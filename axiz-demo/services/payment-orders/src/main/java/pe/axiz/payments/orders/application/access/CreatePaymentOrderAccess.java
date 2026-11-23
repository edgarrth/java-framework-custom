package pe.axiz.payments.orders.application.access;

import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.payments.orders.application.command.CreatePaymentOrderCommand;

/**
 * Solo el cliente autenticado registra sus propias órdenes. El sistema core bancario verifica que
 * la cuenta deudora pertenezca a ese cliente al debitarla (el titular viaja con el débito), por lo
 * que una orden sobre la cuenta de otro cliente se rechaza sin que se mueva dinero.
 */
public class CreatePaymentOrderAccess implements AccessPolicy<CreatePaymentOrderCommand> {

  @Override
  public Class<CreatePaymentOrderCommand> requestType() {
    return CreatePaymentOrderCommand.class;
  }

  @Override
  public void check(CreatePaymentOrderCommand request, Caller caller) {
    PaymentAuthorities.requireCustomer(caller, request.customerId());
  }
}
