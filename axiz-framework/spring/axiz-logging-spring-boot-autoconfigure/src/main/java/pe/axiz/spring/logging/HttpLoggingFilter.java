package pe.axiz.spring.logging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.api.HttpPayloadPolicy;
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
  private final List<HttpPayloadPolicy> payloadPolicies;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public HttpLoggingFilter(
      HttpLogFormatter formatter,
      AxizLoggingProperties.Http settings,
      List<HttpPayloadPolicy> payloadPolicies) {
    this.formatter = Objects.requireNonNull(formatter);
    this.settings = Objects.requireNonNull(settings);
    this.payloadPolicies = List.copyOf(payloadPolicies);
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
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean capture =
        settings.bodies() && payloadPolicies.stream().allMatch(p -> p.permitsCapture(path));
    HttpServletRequest req =
        capture ? new BoundedRequest(request, settings.maxMaskedBodyLength()) : request;
    BoundedCaptureResponseWrapper res =
        capture
            ? new BoundedCaptureResponseWrapper(request, response, settings.maxMaskedBodyLength())
            : null;
    try {
      chain.doFilter(req, res != null ? res : response);
      if (res != null) res.finish();
    } finally {
      long ms = (System.nanoTime() - start) / 1_000_000;
      Map<String, String> headers =
          formatter.headers(Collections.list(request.getHeaderNames()), request::getHeader);
      if (capture && res != null && !request.isAsyncStarted()) {
        BoundedRequest kept = (BoundedRequest) req;
        String in = new String(kept.getContentAsByteArray(), StandardCharsets.UTF_8);
        boolean outComplete = res.eligible() && res.complete();
        String out = outComplete ? new String(res.capturedBytes(), StandardCharsets.UTF_8) : "";
        LOG.info(
            "http method={} path={} status={} durationMs={} headers={} request={} response={}",
            request.getMethod(),
            route(request),
            res.getStatus(),
            ms,
            headers,
            formatter.body(request.getContentType(), in, !kept.overflowed()),
            outComplete
                ? formatter.body(res.getContentType(), out)
                : formatter.omitted(res.getContentType(), res.size()));
      } else {
        LOG.info(
            "http method={} path={} status={} durationMs={} headers={}",
            request.getMethod(),
            route(request),
            response.getStatus(),
            ms,
            headers);
      }
    }
  }

  private static String route(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? "[unmatched]" : pattern.toString();
  }

  /**
   * Keeps at most {@code limit} bytes of the request body and remembers whether the body was
   * longer, so a cut body is never masked as if it were whole.
   */
  static final class BoundedRequest extends ContentCachingRequestWrapper {

    private boolean overflowed;

    BoundedRequest(HttpServletRequest request, int limit) {
      super(request, limit);
    }

    @Override
    protected void handleContentOverflow(int contentCacheLimit) {
      overflowed = true;
    }

    boolean overflowed() {
      return overflowed;
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 30;
  }
}
