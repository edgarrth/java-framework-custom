package pe.axiz.payments.networkgateway.application.port.out;

import pe.axiz.payments.networkgateway.domain.Transfer;
import pe.axiz.payments.networkgateway.domain.TransferOrder;

/** La red de pagos interbancaria. */
public interface PaymentNetwork {

  Transfer send(TransferOrder order, String idempotencyKey);
}
