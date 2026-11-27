package pe.axiz.initializr.service;

import java.util.Objects;

/**
 * Conjunto cerrado de errores de entrada seguros, detectados previo a consumir la autorización
 * delegada.
 */
public final class PublicationInputException extends IllegalArgumentException {
  /** Campo de la solicitud que requiere corrección; no se incluye contenido generado. */
  public enum Field {
    DESCRIPTION,
    FILES,
    CONTENT
  }

  private final Field field;

  public PublicationInputException(Field field) {
    super("Invalid GitHub publication input");
    this.field = Objects.requireNonNull(field);
  }

  public Field field() {
    return field;
  }
}
