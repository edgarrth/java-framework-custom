package pe.axiz.spring.web;

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
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.ProblemFactory;

/**
 * Servlet filter that opens the request context: correlation and request ids (reused only when
 * safe), catalog header validation, MDC entries and the {@code X-Correlation-Id} response header.
 * It runs inside the HTTP server observation, so trace ids are already set.
 */
public class RequestMetadataFilter extends OncePerRequestFilter implements Ordered {

  /** After Boot's server observation filter and before security. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

  private final RequestMetadataResolver resolver;
  private final @Nullable ProblemFactory problems;

  public RequestMetadataFilter(
      RequestMetadataResolver resolver, @Nullable ProblemFactory problems) {
    this.resolver = Objects.requireNonNull(resolver);
    this.problems = problems;
  }

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
