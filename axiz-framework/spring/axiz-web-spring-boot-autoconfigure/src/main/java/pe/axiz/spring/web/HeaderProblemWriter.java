package pe.axiz.spring.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.ProblemFactory;
import pe.axiz.core.errors.ProblemJson;

/** Representa el problema 400 para cabeceras inválidas, con o sin el catálogo de errores. */
final class HeaderProblemWriter {

  private HeaderProblemWriter() {}

  static String body(
      @Nullable ProblemFactory factory,
      List<String> invalidHeaders,
      Locale locale,
      String path,
      String correlationId) {
    String name = invalidHeaders.get(0);
    if (factory != null) {
      Problem p =
          factory.of(
              CoreErrors.MISSING_HEADER, locale, Map.of("header", name), path, correlationId);
      return ProblemJson.write(p);
    }
    return "{\"title\":\"Missing or invalid"
        + " header\",\"status\":400,\"code\":\"AXZ-CORE-0002\",\"detail\":\"Header "
        + name.replaceAll("[^A-Za-z0-9-]", "")
        + " is missing or invalid.\",\"correlationId\":\""
        + correlationId
        + "\"}";
  }
}
