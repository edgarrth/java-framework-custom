package pe.axiz.payments.business.application.access;

import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.payments.business.application.command.ChangePaymentOrderStatusCommand;

/** Status changes belong to the payment process; customers cannot move their own orders. */
public class ChangePaymentOrderStatusAccess
    implements AccessPolicy<ChangePaymentOrderStatusCommand> {

  @Override
  public Class<ChangePaymentOrderStatusCommand> requestType() {
    return ChangePaymentOrderStatusCommand.class;
  }

  @Override
  public void check(ChangePaymentOrderStatusCommand request, Caller caller) {
    if (!caller.isMachine() || !caller.hasAuthority(PaymentAuthorities.PROCESS)) {
      throw new AccessException(CoreErrors.FORBIDDEN, "status changes need payments:process");
    }
  }
}
