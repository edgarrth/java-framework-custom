package pe.axiz.core.http;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.json.JsonReader;

/**
 * Translates an error response from a dependency into an {@link ExternalServiceException}. It
 * understands RFC 9457 problems and common shapes ({@code code/message}, {@code
 * error/error_description}); an empty body is preserved as such.
 */
public final class ExternalErrorTranslator {

  private ExternalErrorTranslator() {}

  public static ExternalServiceException translate(
      String system, int status, @Nullable String contentType, @Nullable String body) {
    String safeBody = body == null ? "" : body;
    String code = null;
    String message = null;
    if (!safeBody.isBlank()
        && contentType != null
        && contentType.toLowerCase(Locale.ROOT).contains("json")) {
      try {
        Map<String, @Nullable Object> json = JsonReader.parseObject(safeBody);
        code = firstString(json, "code", "errorCode", "error");
        message = firstString(json, "detail", "message", "error_description", "title");
      } catch (IllegalArgumentException ignored) {
        // not JSON despite the header: keep the raw body only
      }
    }
    return ExternalServiceException.from(
        new ExternalErrorDetail(system, status, code, message, safeBody));
  }

  private static @Nullable String firstString(Map<String, @Nullable Object> json, String... keys) {
    for (String k : keys) {
      Object v = json.get(k);
      if (v instanceof String s && !s.isBlank()) {
        return s;
      }
      if (v instanceof Number n) {
        return n.toString();
      }
    }
    return null;
  }
}
