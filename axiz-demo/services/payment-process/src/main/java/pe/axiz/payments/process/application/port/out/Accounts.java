package pe.axiz.payments.process.application.port.out;

import pe.axiz.payments.process.domain.PaymentInstruction;

/** Customer accounts (banking-core). */
public interface Accounts {

  /** Debits the debtor account and returns the movement id. */
  String debit(PaymentInstruction instruction, String idempotencyKey);

  /** Reverses a debit (compensation). */
  void reverse(String movementId, String idempotencyKey);
}
