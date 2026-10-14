package pe.axiz.core.errors;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A dependency failed or rejected the call. */
public class ExternalServiceException extends AxizException {

  private static final long serialVersionUID = 1L;

  public ExternalServiceException(
      ErrorDefinition definition, ExternalErrorDetail detail, @Nullable Throwable cause) {
    super(
        definition,
        "Call to " + detail.system() + " failed with status " + detail.status(),
        Map.of("system", detail.system()),
        List.of(),
        detail,
        cause);
  }

  /**
   * Builds the exception from the dependency response, choosing the category from its status.
   *
   * @param detail what the dependency answered
   * @return the exception
   */
  public static ExternalServiceException from(ExternalErrorDetail detail) {
    ErrorCategory category =
        detail.transportError()
            ? ErrorCategory.SERVICE_UNAVAILABLE
            : ErrorCategory.forExternalStatus(detail.status());
    ErrorDefinition def =
        new ErrorDefinition(
            CoreErrors.EXTERNAL_ERROR.code(),
            category,
            ErrorType.EXTERNAL,
            CoreErrors.EXTERNAL_ERROR.title(),
            CoreErrors.EXTERNAL_ERROR.messageKey());
    if (category == ErrorCategory.GATEWAY_TIMEOUT) {
      def = CoreErrors.EXTERNAL_TIMEOUT;
    }
    return new ExternalServiceException(def, detail, null);
  }

  public ExternalErrorDetail detail() {
    ExternalErrorDetail d = external();
    if (d == null) {
      throw new IllegalStateException("external detail missing");
    }
    return d;
  }
}
