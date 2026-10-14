package pe.axiz.core.saga;

import java.util.Objects;

/**
 * What a participant says about a step whose outcome was uncertain (timeout, lost response, server
 * error): the effect was applied, was not applied, or nobody knows yet.
 *
 * @param <C> saga context type
 */
public sealed interface StepCheck<C>
    permits StepCheck.Applied, StepCheck.NotApplied, StepCheck.Unknown {

  /**
   * The effect exists; the saga continues with this context (for example with the movement id the
   * participant reported).
   *
   * @param context context after the step
   */
  record Applied<C>(C context) implements StepCheck<C> {
    public Applied {
      Objects.requireNonNull(context);
    }
  }

  /** The participant confirms the effect does not exist: the step does not need compensation. */
  record NotApplied<C>() implements StepCheck<C> {}

  /** Still uncertain: the saga stays IN_DOUBT and recovery asks again later. */
  record Unknown<C>() implements StepCheck<C> {}

  static <C> StepCheck<C> applied(C context) {
    return new Applied<>(context);
  }

  static <C> StepCheck<C> notApplied() {
    return new NotApplied<>();
  }

  static <C> StepCheck<C> unknown() {
    return new Unknown<>();
  }
}
