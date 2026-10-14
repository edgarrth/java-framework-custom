package pe.axiz.payments.process.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * State of one payment process.
 *
 * @param processId process id (the payment order id)
 * @param status status
 * @param failedStep step that failed, when the process did not complete
 */
public record PaymentProcess(String processId, ProcessStatus status, @Nullable String failedStep) {

  public PaymentProcess {
    Objects.requireNonNull(processId, "processId");
    Objects.requireNonNull(status, "status");
  }
}
