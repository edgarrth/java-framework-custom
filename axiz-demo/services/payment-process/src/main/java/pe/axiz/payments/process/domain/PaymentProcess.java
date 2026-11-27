package pe.axiz.payments.process.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Estado de un proceso de pago.
 *
 * @param processId id del proceso (el id de la orden de pago)
 * @param status estado
 * @param failedStep paso que falló, cuando el proceso no se completó
 */
public record PaymentProcess(String processId, ProcessStatus status, @Nullable String failedStep) {

  public PaymentProcess {
    Objects.requireNonNull(processId, "processId");
    Objects.requireNonNull(status, "status");
  }
}
