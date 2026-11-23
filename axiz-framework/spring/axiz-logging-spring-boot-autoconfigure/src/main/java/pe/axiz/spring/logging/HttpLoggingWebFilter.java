package pe.axiz.spring.logging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.logging.HttpLogFormatter;
import reactor.core.publisher.Mono;

/** Log de acceso reactivo: la misma línea que el filtro servlet, sin cuerpos. */
public class HttpLoggingWebFilter implements WebFilter, Ordered {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.http");

  private final HttpLogFormatter formatter;
  private final List<PathPattern> excluded;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public HttpLoggingWebFilter(HttpLogFormatter formatter, AxizLoggingProperties.Http settings) {
    this.formatter = Objects.requireNonNull(formatter);
    this.excluded =
        settings.excludedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!LOG.isInfoEnabled()
        || excluded.stream()
            .anyMatch(p -> p.matches(exchange.getRequest().getPath().pathWithinApplication()))) {
      return chain.filter(exchange);
    }
    long start = System.nanoTime();
    return chain
        .filter(exchange)
        .doFinally(
            signal -> {
              HttpHeaders h = exchange.getRequest().getHeaders();
              Map<String, String> headers = formatter.headers(h.headerNames(), h::getFirst);
              HttpStatusCode status = exchange.getResponse().getStatusCode();
              LOG.info(
                  "http method={} path={} status={} durationMs={} headers={}",
                  exchange.getRequest().getMethod(),
                  route(exchange),
                  status != null ? status.value() : 0,
                  (System.nanoTime() - start) / 1_000_000,
                  headers);
            });
  }

  private static String route(ServerWebExchange exchange) {
    Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern == null)
      pattern = exchange.getAttribute(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? "[unmatched]" : pattern.toString();
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 30;
  }
}
