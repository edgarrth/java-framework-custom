package pe.axiz.spring.audit;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Decide qué solicitudes se auditan, usando solo coincidencia con {@code PathPattern}. */
final class HttpAuditRules {

  private final Set<String> methods;
  private final List<PathPattern> included;
  private final List<PathPattern> excluded;

  HttpAuditRules(AxizAuditProperties.Http http) {
    this.methods =
        http.methods().stream()
            .map(m -> m.toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    this.included =
        http.includedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    this.excluded =
        http.excludedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
  }

  boolean audited(String method, String path) {
    if (!methods.contains(method.toUpperCase(Locale.ROOT))) {
      return false;
    }
    PathContainer p = PathContainer.parsePath(path);
    return included.stream().anyMatch(x -> x.matches(p))
        && excluded.stream().noneMatch(x -> x.matches(p));
  }
}
