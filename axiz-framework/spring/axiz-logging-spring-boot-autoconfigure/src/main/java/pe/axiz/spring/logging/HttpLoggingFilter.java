package pe.axiz.spring.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.logging.HttpLogFormatter;

/**
 * One access-log line per request (logger {@code axiz.http}) with method, path, status, duration
 * and allow-listed headers. Bodies are optional, JSON only, masked and truncated. Query strings are
 * not logged because they may carry personal data.
 */
public class HttpLoggingFilter extends OncePerRequestFilter implements Ordered {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.http");

  private final HttpLogFormatter formatter;
  private final AxizLoggingProperties.Http settings;
  private final List<PathPattern> excluded;

  public HttpLoggingFilter(HttpLogFormatter formatter, AxizLoggingProperties.Http settings) {
    this.formatter = Objects.requireNonNull(formatter);
    this.settings = Objects.requireNonNull(settings);
    this.excluded =
        settings.excludedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    return !LOG.isInfoEnabled() || excluded.stream().anyMatch(p -> p.matches(path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    HttpServletRequest req =
        settings.bodies()
            ? new ContentCachingRequestWrapper(request, settings.maxBodyLength())
            : request;
    ContentCachingResponseWrapper res =
        settings.bodies() ? new ContentCachingResponseWrapper(response) : null;
    try {
      chain.doFilter(req, res != null ? res : response);
    } finally {
      long ms = (System.nanoTime() - start) / 1_000_000;
      Map<String, String> headers =
          formatter.headers(Collections.list(request.getHeaderNames()), request::getHeader);
      if (settings.bodies() && res != null) {
        String in =
            req instanceof ContentCachingRequestWrapper w
                ? new String(w.getContentAsByteArray(), StandardCharsets.UTF_8)
                : "";
        String out = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
        LOG.info(
            "http method={} path={} status={} durationMs={} headers={} request={} response={}",
            request.getMethod(),
            request.getRequestURI(),
            res.getStatus(),
            ms,
            headers,
            formatter.body(request.getContentType(), in),
            formatter.body(res.getContentType(), out));
        res.copyBodyToResponse();
      } else {
        LOG.info(
            "http method={} path={} status={} durationMs={} headers={}",
            request.getMethod(),
            request.getRequestURI(),
            response.getStatus(),
            ms,
            headers);
      }
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 30;
  }
}
