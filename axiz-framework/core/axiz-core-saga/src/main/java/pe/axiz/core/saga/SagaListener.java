package pe.axiz.core.saga;

import org.jspecify.annotations.Nullable;

/** Observa el progreso de la saga (métricas, trazas, alertas). */
public interface SagaListener {

  default void stepCompleted(String sagaName, String sagaId, String step) {}

  default void compensated(String sagaName, String sagaId, String step) {}

  /**
   * El resultado de un paso es incierto; la saga espera la verificación (alerta pasado un tiempo).
   */
  default void inDoubt(String sagaName, String sagaId, String step) {}

  default void finished(
      String sagaName, String sagaId, SagaStatus status, @Nullable Throwable cause) {}

  static SagaListener none() {
    return new SagaListener() {};
  }
}
