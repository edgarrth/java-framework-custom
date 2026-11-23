package pe.axiz.spring.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.ProblemFactory;

/**
 * Filtro servlet que abre el contexto de la solicitud: ids de correlación y de solicitud
 * (reutilizados solo cuando es seguro), validación de las cabeceras del catálogo, entradas MDC y la
 * cabecera de respuesta {@code X-Correlation-Id}. Se ejecuta dentro de la observación del servidor
 * HTTP, por lo que los ids de traza ya están establecidos.
 */
public class RequestMetadataFilter extends OncePerRequestFilter implements Ordered {

  /** Después del filtro de observación del servidor de Boot y antes de la seguridad. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

  private final RequestMetadataResolver resolver;
  private final @Nullable ProblemFactory problems;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public RequestMetadataFilter(
      RequestMetadataResolver resolver, @Nullable ProblemFactory problems) {
    this.resolver = Objects.requireNonNull(resolver);
    this.problems = problems;
  }

  @SuppressFBWarnings(
      value = "XSS_SERVLET",
      justification =
          "HeaderProblemWriter serializes JSON-escaped Problem data with "
              + "application/problem+json and nosniff; request values are never interpreted as "
              + "HTML.")
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    RequestMetadata metadata =
        resolver.resolve(request::getHeader, LocaleContextHolder.getLocale());
    response.setHeader(StandardHeaders.CORRELATION_ID, metadata.correlationId());
    String path = request.getRequestURI().substring(request.getContextPath().length());
    MDC.put(MdcKeys.CORRELATION_ID, metadata.correlationId());
    MDC.put(MdcKeys.REQUEST_ID, metadata.requestId());
    try (RequestContext.Scope ignored = RequestContext.open(metadata)) {
      List<String> invalid = resolver.invalidHeaders(path, request::getHeader);
      if (!invalid.isEmpty()) {
        response.setStatus(400);
        response.setContentType(Problem.MEDIA_TYPE);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response
            .getWriter()
            .write(
                HeaderProblemWriter.body(
                    problems,
                    invalid,
                    metadata.locale(),
                    request.getRequestURI(),
                    metadata.correlationId()));
        return;
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
      MDC.remove(MdcKeys.REQUEST_ID);
    }
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
