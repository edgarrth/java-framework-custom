package pe.axiz.core.saga;

import java.util.Set;
import pe.axiz.core.errors.AxizException;

/**
 * Clasifica los fallos de los pasos: rechazos definitivos (el efecto no existe) frente a resultados
 * inciertos (el efecto puede existir).
 */
public final class StepFailures {

  /** Estados 4xx que no prueban que la petición no se procesó. */
  private static final Set<Integer> UNCERTAIN_4XX = Set.of(408, 409, 425, 429);

  private StepFailures() {}

  /**
   * {@code true} para {@link SagaStepRejectedException} y para errores de axiz en una categoría de
   * error de cliente (400, 401, 403, 404, 412, 422, etc.: el participante rechazó la petición). Los
   * timeouts, errores de servidor, errores de transporte, conflictos y cualquier otra cosa son
   * inciertos.
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
