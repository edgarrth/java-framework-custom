package pe.axiz.core.saga;

import java.util.Set;
import pe.axiz.core.errors.AxizException;

/**
 * Classifies step failures: definite rejections (the effect does not exist) against uncertain
 * outcomes (the effect may exist).
 */
public final class StepFailures {

  /** 4xx statuses that do not prove the request was not processed. */
  private static final Set<Integer> UNCERTAIN_4XX = Set.of(408, 409, 425, 429);

  private StepFailures() {}

  /**
   * {@code true} for {@link SagaStepRejectedException} and for axiz errors in a client-error
   * category (400, 401, 403, 404, 412, 422...: the participant refused the request). Timeouts,
   * server errors, transport errors, conflicts and anything else are uncertain.
   */
  public static boolean isDefiniteRejection(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SagaStepRejectedException) {
        return true;
      }
      if (t instanceof AxizException e) {
        int status = e.category().httpStatus();
        return status >= 400 && status < 500 && !UNCERTAIN_4XX.contains(status);
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
