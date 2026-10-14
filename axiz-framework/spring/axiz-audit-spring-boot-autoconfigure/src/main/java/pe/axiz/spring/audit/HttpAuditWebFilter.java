package pe.axiz.spring.audit;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import pe.axiz.core.audit.AuditTrail;
import reactor.core.publisher.Mono;

/** Reactive counterpart of {@link HttpAuditFilter}. */
public class HttpAuditWebFilter implements WebFilter, Ordered {

  private final AuditTrail trail;
  private final AuditContextResolver context;
  private final HttpAuditRules rules;

  public HttpAuditWebFilter(
      AuditTrail trail, AuditContextResolver context, AxizAuditProperties.Http http) {
    this.trail = Objects.requireNonNull(trail);
    this.context = Objects.requireNonNull(context);
    this.rules = new HttpAuditRules(http);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String method = exchange.getRequest().getMethod().name();
    if (!rules.audited(method, exchange.getRequest().getPath().pathWithinApplication().value())) {
      return chain.filter(exchange);
    }
    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
    AuditTrail.Context ctx =
        context.current(
            remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : null);
    return chain
        .filter(exchange)
        .doFinally(
            signal -> {
              PathPattern pattern =
                  exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
              HttpStatusCode status = exchange.getResponse().getStatusCode();
              int code = status != null ? status.value() : 200;
              AuditTrail.Builder record =
                  trail
                      .record(
                          "http." + method.toLowerCase(Locale.ROOT),
                          pattern != null ? pattern.getPatternString() : "unmatched")
                      .context(ctx)
                      .detail("status", code);
              try {
                if (code >= 400) {
                  record.failure("HTTP-" + code);
                } else {
                  record.success();
                }
              } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(HttpAuditWebFilter.class)
                    .warn("Audit event could not be recorded", e);
              }
            });
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
