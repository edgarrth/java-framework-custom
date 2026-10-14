package pe.axiz.core.saga;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Definite answer of a participant: the step was rejected and its effect does not exist (for
 * example insufficient funds). The saga compensates the previous steps without asking the
 * participant again. Throw it only when the rejection is certain; any other failure is treated as
 * an uncertain outcome.
 */
public class SagaStepRejectedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public SagaStepRejectedException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
