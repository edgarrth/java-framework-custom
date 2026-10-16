package pe.axiz.core.errors;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/** A dependency failed or rejected the call. */
public class ExternalServiceException extends AxizException {

  private static final long serialVersionUID = 1L;

  public ExternalServiceException(
      ErrorDefinition definition, ExternalErrorDetail detail, @Nullable Throwable cause) {
    super(definition, message(detail), Map.of("system", detail.system()), List.of(), detail, cause);
  }

  /**
   * Builds the exception from the dependency response, choosing the category from its status.
   *
   * @param detail what the dependency answered
   * @return the exception
   */
  public static ExternalServiceException from(ExternalErrorDetail detail) {
    return from(detail, null);
  }

  /**
   * Builds the exception from the dependency response or transport failure, keeping the cause. A
   * transport failure is a timeout (504) when the cause is one, otherwise the dependency is
   * unavailable (503: refused or reset connection, open circuit, full bulkhead).
   *
   * @param detail what the dependency answered (status -1 when it did not answer)
   * @param cause the transport failure, if any
   * @return the exception
   */
  public static ExternalServiceException from(
      ExternalErrorDetail detail, @Nullable Throwable cause) {
    ErrorCategory category;
    if (!detail.transportError()) {
      category = ErrorCategory.forExternalStatus(detail.status());
    } else if (isTimeout(cause)) {
      category = ErrorCategory.GATEWAY_TIMEOUT;
    } else {
      category = ErrorCategory.SERVICE_UNAVAILABLE;
    }
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
    return new ExternalServiceException(def, detail, cause);
  }

  /**
   * {@code true} when the failure, or one of its causes, is a timeout: the JDK ones and those of
   * HTTP client libraries this module does not depend on (Netty's {@code ReadTimeoutException}, for
   * example), recognized by the {@code TimeoutException} suffix of their type.
   */
  static boolean isTimeout(@Nullable Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof SocketTimeoutException
          || t instanceof HttpTimeoutException
          || t instanceof TimeoutException
          || t.getClass().getSimpleName().endsWith("TimeoutException")) {
        return true;
      }
    }
    return false;
  }

  // a transport failure has no status to show: "failed with status -1" said nothing
  private static String message(ExternalErrorDetail detail) {
    if (!detail.transportError()) {
      return "Call to " + detail.system() + " failed with status " + detail.status();
    }
    String reason = detail.code() != null ? detail.code() : detail.message();
    return "Call to "
        + detail.system()
        + " got no response"
        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "");
  }

  public ExternalErrorDetail detail() {
    ExternalErrorDetail d = external();
    if (d == null) {
      throw new IllegalStateException("external detail missing");
    }
    return d;
  }
}
