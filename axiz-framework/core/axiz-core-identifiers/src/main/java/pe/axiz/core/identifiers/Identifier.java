package pe.axiz.core.identifiers;

import java.util.Objects;

/**
 * Objeto de valor de identificador validado.
 *
 * @param type nombre del tipo
 * @param value valor normalizado
 */
public record Identifier(String type, String value) {

  public Identifier {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(value, "value");
  }

  /** Texto enmascarado, seguro para logs. */
  public String masked(IdentifierRegistry registry) {
    return registry.require(type).mask(value);
  }

  @Override
  public String toString() {
    return type
        + ":"
        + "*".repeat(Math.max(0, value.length() - 4))
        + value.substring(Math.max(0, value.length() - 4));
  }
}
