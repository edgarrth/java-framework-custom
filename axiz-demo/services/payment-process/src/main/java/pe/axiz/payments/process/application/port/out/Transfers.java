package pe.axiz.payments.process.application.port.out;

import pe.axiz.payments.process.domain.PaymentInstruction;

/** Transferencias interbancarias (payment-network-gateway delante de la red de pagos). */
public interface Transfers {

  /** Envía la transferencia y devuelve el id en la red. */
  String send(PaymentInstruction instruction, String idempotencyKey);
}
