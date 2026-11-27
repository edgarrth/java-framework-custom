package pe.axiz.payments.process.application.usecase;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.payments.process.domain.PaymentProcess;
import pe.axiz.payments.process.domain.ProcessStatus;

/**
 * Convierte el estado persistido de la saga en la vista del proceso (ningún dato del contexto sale
 * del servicio).
 */
final class ProcessViews {

  private ProcessViews() {}

  static PaymentProcess of(SagaState state) {
    return new PaymentProcess(state.sagaId(), status(state), failedStep(state.error()));
  }

  /**
   * Estado visible de una saga persistida. Antes de cada llamada a un participante, el orquestador
   * guarda la intención como {@code IN_DOUBT} sin error: el paso está en curso, no verificándose.
   * Solo un {@code IN_DOUBT} con el error del paso es un resultado incierto que se verifica.
   */
  static ProcessStatus status(SagaState state) {
    if (state.status() == SagaStatus.IN_DOUBT && state.error() == null) {
      return ProcessStatus.RUNNING;
    }
    return status(state.status());
  }

  static ProcessStatus status(SagaStatus s) {
    return switch (s) {
      case RUNNING -> ProcessStatus.RUNNING;
      case COMPENSATING -> ProcessStatus.COMPENSATING;
      case IN_DOUBT -> ProcessStatus.VERIFYING;
      case COMPLETED -> ProcessStatus.COMPLETED;
      case COMPENSATED -> ProcessStatus.REJECTED;
      case FAILED -> ProcessStatus.NEEDS_ATTENTION;
    };
  }

  /** Los errores se almacenan como {@code step: cause} o {@code compensation step: cause}. */
  static @Nullable String failedStep(@Nullable String error) {
    if (error == null || !error.contains(":")) {
      return null;
    }
    String head = error.substring(0, error.indexOf(':'));
    return head.startsWith("compensation ") ? head.substring("compensation ".length()) : head;
  }
}
