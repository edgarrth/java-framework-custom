package pe.axiz.core.sensitive;

/** Grado de daño que causaría revelar un valor. */
public enum SensitivityLevel {
  /** Interno pero inofensivo (por ejemplo, un código de producto). */
  LOW,
  /** Datos personales (nombre, email, teléfono). */
  MEDIUM,
  /** Datos identificatorios o financieros (documento, cuenta, tarjeta parcial). */
  HIGH,
  /**
   * Secretos y datos completos de tarjeta (contraseña, PIN, CVV, PAN, tokens). Nunca se muestran en
   * ningún lugar.
   */
  CRITICAL
}
