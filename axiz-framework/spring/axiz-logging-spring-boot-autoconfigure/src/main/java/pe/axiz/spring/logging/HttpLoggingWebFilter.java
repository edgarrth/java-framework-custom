package pe.axiz.spring.logging;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.logging.HttpLogFormatter;
import reactor.core.publisher.Mono;

/** Reactive access log: same line as the servlet filter, without bodies. */
public class HttpLoggingWebFilter implements WebFilter, Ordered {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.http");

  private final HttpLogFormatter formatter;
  private final List<PathPattern> excluded;

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
                  exchange.getRequest().getPath().value(),
                  status != null ? status.value() : 0,
                  (System.nanoTime() - start) / 1_000_000,
                  headers);
            });
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 30;
  }
}
