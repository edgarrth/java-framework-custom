package pe.axiz.payments.process.domain;

/** Status of a payment process as the API shows it. */
public enum ProcessStatus {
  /** Steps are running. */
  RUNNING,
  /**
   * The outcome of a debit or transfer is uncertain (timeout, lost answer): it is being verified
   * with the participant and nothing is undone meanwhile.
   */
  VERIFYING,
  /** A step failed and completed steps are being undone. */
  COMPENSATING,
  /** Debit and transfer done; the order is completed. */
  COMPLETED,
  /** A step failed and everything was undone; the order is rejected. */
  REJECTED,
  /** An undo failed: operations must review the payment. */
  NEEDS_ATTENTION
}
