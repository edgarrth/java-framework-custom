package pe.axiz.core.domain;

import java.util.Map;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorDefinition;

/** Cláusulas de guarda para agregados que lanzan errores de negocio del catálogo. */
public final class Rules {

  private Rules() {}

  /**
   * Falla con el error de negocio indicado cuando la condición es falsa.
   *
   * @param condition regla que debe cumplirse
   * @param error definición del catálogo a lanzar
   * @param logMessage mensaje para logs (nunca se muestra a los clientes)
   */
  public static void check(boolean condition, ErrorDefinition error, String logMessage) {
    if (!condition) {
      throw new BusinessException(error, logMessage);
    }
  }

  public static void check(
      boolean condition, ErrorDefinition error, String logMessage, Map<String, ?> parameters) {
    if (!condition) {
      throw new BusinessException(error, logMessage, parameters);
    }
  }
}
