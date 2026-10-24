package pe.axiz.payments.coreintegration.application.port.out;

import pe.axiz.payments.coreintegration.domain.Transfer;
import pe.axiz.payments.coreintegration.domain.TransferOrder;

/** The inter-bank payment network. */
public interface PaymentNetwork {

  Transfer send(TransferOrder order, String idempotencyKey);
}
