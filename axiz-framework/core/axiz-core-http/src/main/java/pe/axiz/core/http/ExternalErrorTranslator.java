package pe.axiz.core.http;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.json.JsonReader;

/**
 * Traduce una respuesta de error de una dependencia a una {@link ExternalServiceException}.
 * Entiende problemas RFC 9457 y formas comunes ({@code code/message}, {@code
 * error/error_description}); un cuerpo vacío se preserva como tal.
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
        // no es JSON pese a la cabecera: se conserva solo el cuerpo en bruto
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
