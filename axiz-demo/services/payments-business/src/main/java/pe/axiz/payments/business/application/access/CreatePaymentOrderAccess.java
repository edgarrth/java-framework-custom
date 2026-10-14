package pe.axiz.payments.business.application.access;

import org.springframework.stereotype.Component;
import pe.axiz.framework.cqrs.AccessPolicy;
import pe.axiz.framework.cqrs.Caller;
import pe.axiz.payments.business.application.command.CreatePaymentOrderCommand;

/**
 * Only the signed-in customer registers its own orders. Whether the debtor account belongs to that
 * customer is checked by the core banking system when the account is debited (the holder travels
 * with the debit), so an order on another customer's account is rejected before money moves.
 */
@Component
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
