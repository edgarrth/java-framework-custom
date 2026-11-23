package pe.axiz.core.identifiers;

import java.util.Locale;

/**
 * Interfaz de proveedor de servicio para cualquier tipo de identificador (documento, cuenta,
 * tarjeta, contacto, etc.). Las implementaciones deben ser sin estado y thread-safe.
 */
public interface IdentifierType {

  /** Nombre único en minúsculas usado en configuración y anotaciones, por ejemplo {@code "dni"}. */
  String name();

  /** Valida un valor ya normalizado. */
  boolean isValid(String normalizedValue);

  /**
   * Normaliza la entrada del usuario antes de validarla: elimina espacios y guiones y pasa a
   * mayúsculas.
   */
  default String normalize(String raw) {
    return raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Enmascara el valor para logs y respuestas; por defecto revela como máximo un 25% y cuatro
   * caracteres del sufijo.
   */
  default String mask(String normalizedValue) {
    return IdentifierMasks.tail(normalizedValue, 4);
  }
}
