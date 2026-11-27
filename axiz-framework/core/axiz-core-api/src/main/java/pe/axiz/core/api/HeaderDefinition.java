package pe.axiz.core.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.lang.Checks;

/**
 * Declara una cabecera de negocio en el catálogo de cabeceras.
 *
 * @param name nombre de la cabecera tal como se envía por la red
 * @param required indica si las peticiones entrantes deben llevarla
 * @param pattern patrón de validación opcional
 * @param propagate indica si se reenvía a las llamadas y mensajes salientes
 * @param sensitive indica si su valor debe enmascararse en logs y auditoría
 * @param maxLength longitud máxima aceptada
 */
public record HeaderDefinition(
    String name,
    boolean required,
    @Nullable Pattern pattern,
    boolean propagate,
    boolean sensitive,
    int maxLength) {

  public HeaderDefinition {
    Checks.notBlank(name, "name");
    Checks.positive(maxLength, "maxLength");
  }

  public static HeaderDefinition optional(String name) {
    return new HeaderDefinition(name, false, null, true, false, 256);
  }

  public static HeaderDefinition required(String name) {
    return new HeaderDefinition(name, true, null, true, false, 256);
  }

  public HeaderDefinition withPattern(String regex) {
    return new HeaderDefinition(
        name, required, Pattern.compile(regex), propagate, sensitive, maxLength);
  }

  public HeaderDefinition notPropagated() {
    return new HeaderDefinition(name, required, pattern, false, sensitive, maxLength);
  }

  public HeaderDefinition asSensitive() {
    return new HeaderDefinition(name, required, pattern, propagate, true, maxLength);
  }

  /** Clave en minúsculas para búsquedas sin distinción de mayúsculas. */
  public String key() {
    return name.toLowerCase(Locale.ROOT);
  }

  /**
   * Valida el valor de una cabecera contra esta definición.
   *
   * @param value valor recibido (puede ser null)
   * @return un mensaje de error o null cuando es válido
   */
  public @Nullable String validate(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return required ? "Header " + name + " is required" : null;
    }
    if (value.length() > maxLength) {
      return "Header " + name + " exceeds " + maxLength + " characters";
    }
    if (pattern != null && !pattern.matcher(value).matches()) {
      return "Header " + name + " has an invalid format";
    }
    return null;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof HeaderDefinition other && key().equals(other.key());
  }

  @Override
  public int hashCode() {
    return Objects.hash(key());
  }
}
