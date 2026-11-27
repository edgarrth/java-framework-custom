package pe.axiz.core.identifiers;

import org.jspecify.annotations.Nullable;

/**
 * Enmascaramiento canónico para presentación compartido por los objetos de valor de identificador y
 * las políticas de sensibilidad.
 */
public final class IdentifierMasks {
  public static final String FULL = "****";

  private IdentifierMasks() {}

  /**
   * Como máximo una cuarta parte de los identificadores suficientemente largos, limitada por el
   * sufijo solicitado.
   */
  public static String tail(@Nullable String value, int requested) {
    if (value == null || value.length() < 8 || requested < 1) return FULL;
    int visible = Math.min(requested, Math.min(4, value.length() / 4));
    return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
  }

  /**
   * El dominio sigue visible; una parte local de menos de cuatro caracteres se enmascara por
   * completo.
   */
  public static String email(@Nullable String value) {
    if (value == null) return FULL;
    int at = value.indexOf('@');
    if (at <= 0 || value.indexOf('@', at + 1) >= 0) return FULL;
    return (at < 4 ? FULL : value.charAt(0) + "*".repeat(at - 1)) + value.substring(at);
  }

  /**
   * Presentación conservadora de un PAN completo: solo los seis primeros y los cuatro últimos
   * dígitos.
   */
  public static String card(@Nullable String value) {
    if (value == null) return FULL;
    String digits = value.replaceAll("\\D", "");
    if (digits.length() < 13 || digits.length() > 19) return FULL;
    return digits.substring(0, 6)
        + "*".repeat(digits.length() - 10)
        + digits.substring(digits.length() - 4);
  }
}
