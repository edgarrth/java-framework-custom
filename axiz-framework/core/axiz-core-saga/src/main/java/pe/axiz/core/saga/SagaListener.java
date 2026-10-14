package pe.axiz.core.saga;

import org.jspecify.annotations.Nullable;

/** Observes saga progress (metrics, traces, alerts). */
public interface SagaListener {

  default void stepCompleted(String sagaName, String sagaId, String step) {}

  default void compensated(String sagaName, String sagaId, String step) {}

  /** A step's outcome is uncertain; the saga waits for verification (alert after a while). */
  default void inDoubt(String sagaName, String sagaId, String step) {}

  default void finished(
      String sagaName, String sagaId, SagaStatus status, @Nullable Throwable cause) {}

  static SagaListener none() {
    return new SagaListener() {};
  }
}
