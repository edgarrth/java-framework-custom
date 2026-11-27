package pe.axiz.core.errors;

/** Naturaleza de un error, independiente de su representación HTTP. */
public enum ErrorType {
  /** Una regla de negocio rechazó la operación (resultado esperado). */
  BUSINESS,
  /** La entrada está mal formada o incompleta. */
  VALIDATION,
  /** Fallo de autenticación o de autorización. */
  SECURITY,
  /** Fallo inesperado dentro del servicio. */
  TECHNICAL,
  /** Una dependencia (tercero, sistema core, otro servicio) falló o rechazó la llamada. */
  EXTERNAL
}
