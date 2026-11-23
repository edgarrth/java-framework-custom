package pe.axiz.core.saga;

import java.util.Objects;

/**
 * Identidad de ejecución del participante. La saga y el paso forman una clave de operación estable;
 * el fence crece con las transiciones durables y los propietarios, y se mantiene igual en los
 * reintentos del mismo intento. La compensación usa una operación del participante distinta de la
 * ejecución. Un participante debe aplicar los fences de forma atómica; reenviar este valor no
 * aplica fencing a la E/S por sí solo.
 */
public record SagaStepExecution(String sagaId, String stepName, long fence) {
  public SagaStepExecution {
    Objects.requireNonNull(sagaId);
    Objects.requireNonNull(stepName);
    if (sagaId.isBlank() || stepName.isBlank() || fence < 1)
      throw new IllegalArgumentException("Invalid saga execution identity");
  }
}
