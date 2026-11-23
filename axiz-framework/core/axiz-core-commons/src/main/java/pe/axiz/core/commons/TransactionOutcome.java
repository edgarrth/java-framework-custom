package pe.axiz.core.commons;

/** Evidencia sobre una transacción local; un rollback no prueba la ausencia de efectos externos. */
public enum TransactionOutcome {
  COMMITTED,
  ROLLED_BACK,
  UNKNOWN
}
