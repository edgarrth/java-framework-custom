package pe.axiz.core.saga;

/**
 * One step of a saga: an action, its compensation and, for remote effects, how to verify an
 * uncertain outcome. Action and compensation must be idempotent because a resumed saga may repeat
 * the last step.
 *
 * @param <C> saga context type
 */
public interface SagaStep<C> {

  String name();

  /** Executes the step and returns the (possibly enriched) context. */
  C execute(C context) throws Exception;

  /** Undoes the step after a later step failed. */
  void compensate(C context) throws Exception;

  /**
   * Asks the participant whether the effect of {@link #execute} exists, after it failed without a
   * definite rejection (timeout, lost response, server error). Typically a status query by the
   * step's idempotency key, or the same idempotent call repeated with that key. The default answer
   * is {@link StepCheck#unknown()}: the saga goes IN_DOUBT and nothing is compensated until the
   * outcome is known (or an operator decides).
   */
  default StepCheck<C> verify(C context) throws Exception {
    return StepCheck.unknown();
  }
}
