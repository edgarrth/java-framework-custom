package pe.axiz.payments.process.application.port.out;

import pe.axiz.payments.process.domain.PaymentInstruction;

/** Interbank transfers (core-integration in front of the payment network). */
public interface Transfers {

  /** Sends the transfer and returns the network id. */
  String send(PaymentInstruction instruction, String idempotencyKey);
}
