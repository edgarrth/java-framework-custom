package pe.axiz.spring.web;

import java.io.IOException;
import java.util.List;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Límites de solicitud compilados, por primera coincidencia, compartidos por las pilas HTTP. */
final class RequestBodyLimits {
  private record Rule(PathPattern path, long bytes) {}

  private final long defaultBytes;
  private final List<Rule> rules;

  RequestBodyLimits(AxizWebProperties properties) {
    defaultBytes = properties.maxRequestBody().toBytes();
    rules =
        properties.bodyLimits().stream()
            .map(
                r ->
                    new Rule(
                        PathPatternParser.defaultInstance.parse(r.path()), r.maxSize().toBytes()))
            .toList();
  }

  long forPath(String path) {
    PathContainer parsed = PathContainer.parsePath(path);
    return rules.stream()
        .filter(r -> r.path().matches(parsed))
        .findFirst()
        .map(Rule::bytes)
        .orElse(defaultBytes);
  }

  static String problem() {
    return "{\"type\":\"about:blank\",\"title\":\"Request body too"
        + " large\",\"status\":413,\"code\":\"AXZ-CORE-0413\"}";
  }

  static boolean exceeded(Throwable failure) {
    for (Throwable cause = failure;
        cause != null && cause != cause.getCause();
        cause = cause.getCause())
      if (cause instanceof BodyTooLarge || cause instanceof DataBufferLimitException) return true;
    return false;
  }

  static final class BodyTooLarge extends IOException {
    BodyTooLarge() {
      super("Request body exceeds configured limit");
    }
  }
}
