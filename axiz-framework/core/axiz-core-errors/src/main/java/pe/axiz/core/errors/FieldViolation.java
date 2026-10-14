package pe.axiz.core.errors;

import org.jspecify.annotations.Nullable;

/**
 * A validation failure on a single field.
 *
 * @param field field path, e.g. {@code beneficiary.account}
 * @param code constraint code, e.g. {@code NotBlank}
 * @param message human readable message (already localized)
 * @param rejectedValue masked rejected value, if safe to show
 */
public record FieldViolation(
    String field, String code, String message, @Nullable String rejectedValue) {

  public static FieldViolation of(String field, String code, String message) {
    return new FieldViolation(field, code, message, null);
  }
}
