package pe.axiz.core.errors;

import java.io.Serializable;
import java.util.Objects;

/**
 * Catalog entry describing an error.
 *
 * @param code error code
 * @param category category (and therefore HTTP status)
 * @param type error type
 * @param title short, human readable summary (default language)
 * @param messageKey i18n key for the detail message
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
