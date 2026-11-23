package pe.axiz.spring.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import pe.axiz.core.errors.Problem;

/**
 * Manejo de 401 y 403 en servlet: cuerpo de problema más {@code WWW-Authenticate: Bearer}. Spring
 * Security registra en nivel debug el motivo por el que se rechazó un token y nunca se envía al
 * cliente.
 */
public class ProblemAuthenticationEntryPoint
    implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final SecurityProblems problems;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public ProblemAuthenticationEntryPoint(SecurityProblems problems) {
    this.problems = Objects.requireNonNull(problems);
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    var unavailable = IssuerUnavailableException.find(e);
    if (unavailable != null) {
      response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(unavailable.retryAfterSeconds()));
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      write(
          response,
          503,
          problems.unavailable(LocaleContextHolder.getLocale(), request.getRequestURI()));
      return;
    }
    boolean hadToken = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE, hadToken ? "Bearer error=\"invalid_token\"" : "Bearer");
    write(
        response,
        401,
        problems.unauthorized(LocaleContextHolder.getLocale(), request.getRequestURI()));
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
    write(
        response,
        403,
        problems.forbidden(LocaleContextHolder.getLocale(), request.getRequestURI()));
  }

  @SuppressFBWarnings(
      value = "XSS_SERVLET",
      justification =
          "SecurityProblems serializes JSON-escaped Problem data with "
              + "application/problem+json and nosniff, not an HTML document.")
  static void write(HttpServletResponse response, int status, String body) throws IOException {
    response.setStatus(status);
    response.setContentType(Problem.MEDIA_TYPE);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body);
  }
}
