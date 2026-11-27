package pe.axiz.payments.process.application.port.out;

import pe.axiz.payments.process.domain.PaymentInstruction;

/** Cuentas de clientes (banking-core). */
public interface Accounts {

  /** Debita la cuenta deudora y devuelve el id del movimiento. */
  String debit(PaymentInstruction instruction, String idempotencyKey);

  /** Revierte un débito (compensación). */
  void reverse(String movementId, String idempotencyKey);
}
