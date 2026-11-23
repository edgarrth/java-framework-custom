package pe.axiz.core.lock;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Errores de la capacidad de locks. */
public final class LockErrors {

  public static final ErrorDefinition BUSY =
      ErrorDefinition.of("AXZ-LOCK-0409", ErrorCategory.CONFLICT, "Resource busy, retry later");

  /**
   * El lease terminó antes que el trabajo: otro propietario puede haber tomado el recurso, así que
   * el resultado del trabajo no es confiable y no debe informarse como éxito.
   */
  public static final ErrorDefinition LOST =
      ErrorDefinition.of(
          "AXZ-LOCK-0503",
          ErrorCategory.SERVICE_UNAVAILABLE,
          "Lock lost before the work finished, retry later");

  private LockErrors() {}
}
