package pe.axiz.spring.security;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.ProblemFactory;
import pe.axiz.core.errors.ProblemJson;

/** Renders 401, 403 and 429 responses as RFC 9457 problems with catalog codes. */
public class SecurityProblems {

  private final @Nullable ProblemFactory factory;

  public SecurityProblems(@Nullable ProblemFactory factory) {
    this.factory = factory;
  }

  public String unauthorized(Locale locale, String path) {
    return body(CoreErrors.UNAUTHENTICATED, locale, path);
  }

  public String forbidden(Locale locale, String path) {
    return body(CoreErrors.FORBIDDEN, locale, path);
  }

  public String tooManyRequests(Locale locale, String path) {
    return body(CoreErrors.TOO_MANY_REQUESTS, locale, path);
  }

  public String unavailable(Locale locale, String path) {
    return body(CoreErrors.UNAVAILABLE, locale, path);
  }

  private String body(ErrorDefinition definition, Locale locale, String path) {
    RequestMetadata m = RequestContext.get();
    String correlationId = m != null ? m.correlationId() : null;
    if (factory != null) {
      return ProblemJson.write(factory.of(definition, locale, Map.of(), path, correlationId));
    }
    return "{\"title\":\""
        + definition.title()
        + "\",\"status\":"
        + definition.category().httpStatus()
        + ",\"code\":\""
        + definition.code().value()
        + "\"}";
  }
}
