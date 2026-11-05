package pe.axiz.core.commons;

/** Evidence about a local transaction; rollback does not prove absence of external effects. */
public enum TransactionOutcome {
  COMMITTED,
  ROLLED_BACK,
  UNKNOWN
}
