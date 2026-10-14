package pe.axiz.payments.process.application.usecase;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.saga.SagaState;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.payments.process.domain.PaymentProcess;
import pe.axiz.payments.process.domain.ProcessStatus;

/** Turns persisted saga state into the process view (no context data leaves the service). */
final class ProcessViews {

  private ProcessViews() {}

  static PaymentProcess of(SagaState state) {
    return new PaymentProcess(state.sagaId(), status(state.status()), failedStep(state.error()));
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

  /** Errors are stored as {@code step: cause} or {@code compensation step: cause}. */
  static @Nullable String failedStep(@Nullable String error) {
    if (error == null || !error.contains(":")) {
      return null;
    }
    String head = error.substring(0, error.indexOf(':'));
    return head.startsWith("compensation ") ? head.substring("compensation ".length()) : head;
  }
}
