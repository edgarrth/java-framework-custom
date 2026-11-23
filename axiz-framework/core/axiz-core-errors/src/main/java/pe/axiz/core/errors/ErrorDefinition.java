package pe.axiz.core.errors;

import java.io.Serializable;
import java.util.Objects;

/**
 * Entrada del catálogo que describe un error.
 *
 * @param code código de error
 * @param category categoría (y por tanto estado HTTP)
 * @param type tipo de error
 * @param title resumen breve y legible (idioma por defecto)
 * @param messageKey clave i18n del mensaje de detalle
 */
public record ErrorDefinition(
    ErrorCode code, ErrorCategory category, ErrorType type, String title, String messageKey)
    implements Serializable {

  public ErrorDefinition {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(messageKey, "messageKey");
  }

  public static ErrorDefinition of(String code, ErrorCategory category, String title) {
    return new ErrorDefinition(
        ErrorCode.of(code), category, category.defaultType(), title, "error." + code);
  }

  public static ErrorDefinition of(
      String code, ErrorCategory category, ErrorType type, String title) {
    return new ErrorDefinition(ErrorCode.of(code), category, type, title, "error." + code);
  }
}
