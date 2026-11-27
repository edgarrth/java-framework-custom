package pe.axiz.core.errors;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * Fallo de validación en un único campo.
 *
 * @param field ruta del campo, por ejemplo {@code beneficiary.account}
 * @param code código de la restricción, por ejemplo {@code NotBlank}
 * @param message mensaje legible (ya localizado)
 * @param rejectedValue valor rechazado enmascarado, si es seguro mostrarlo
 */
public record FieldViolation(
    String field, String code, String message, @Nullable String rejectedValue)
    implements Serializable {

  public static FieldViolation of(String field, String code, String message) {
    return new FieldViolation(field, code, message, null);
  }
}
