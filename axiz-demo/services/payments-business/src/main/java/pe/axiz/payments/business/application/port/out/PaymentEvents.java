package pe.axiz.payments.business.application.port.out;

import pe.axiz.payments.business.domain.PaymentOrder;
import pe.axiz.payments.business.domain.PaymentStatus;

/** Events other services react to; published in the same transaction as the change (outbox). */
public interface PaymentEvents {

  void created(PaymentOrder order);

  void statusChanged(PaymentOrder order, PaymentStatus previous);
}
