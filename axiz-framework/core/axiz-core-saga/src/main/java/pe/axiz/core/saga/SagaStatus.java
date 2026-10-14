package pe.axiz.core.saga;

/** Saga lifecycle. */
public enum SagaStatus {
  RUNNING,
  COMPLETED,
  COMPENSATING,
  COMPENSATED,
  /**
   * A step failed with an uncertain outcome (the effect may exist): nothing is compensated or
   * declared until verification answers; recovery asks again.
   */
  IN_DOUBT,
  /** Compensation failed: needs manual intervention. */
  FAILED;

  public boolean terminal() {
    return this == COMPLETED || this == COMPENSATED || this == FAILED;
  }
}
