package pe.axiz.payments.orders.application.port.out;

import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;

/**
 * Eventos a los que reaccionan otros servicios; se publican en la misma transacción que el cambio
 * (outbox).
 */
public interface PaymentEvents {

  void created(PaymentOrder order);

  void statusChanged(PaymentOrder order, PaymentStatus previous);
}
