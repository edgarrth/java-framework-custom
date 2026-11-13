package pe.axiz.spring.audit;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.errors.AxizException;
import reactor.core.publisher.Mono;

/** Reactive counterpart of {@link HttpAuditFilter}. */
public final class HttpAuditWebFilter implements WebFilter, Ordered {

  private final @Nullable AuditTrail trail;
  private final @Nullable ReactiveAuditTrail nativeTrail;
  private final AuditContextResolver context;
  private final HttpAuditRules rules;

  public HttpAuditWebFilter(
      AuditTrail trail, AuditContextResolver context, AxizAuditProperties.Http http) {
    this(
        Objects.requireNonNull(trail),
        null,
        Objects.requireNonNull(context),
        new HttpAuditRules(http));
  }

  /** Native variant; audit completion participates in the request's reactive lifecycle. */
  public HttpAuditWebFilter(
      ReactiveAuditTrail trail, AuditContextResolver context, AxizAuditProperties.Http http) {
    this(
        null,
        Objects.requireNonNull(trail),
        Objects.requireNonNull(context),
        new HttpAuditRules(http));
  }

  private HttpAuditWebFilter(
      @Nullable AuditTrail trail,
      @Nullable ReactiveAuditTrail nativeTrail,
      AuditContextResolver context,
      HttpAuditRules rules) {
    this.trail = trail;
    this.nativeTrail = nativeTrail;
    this.context = context;
    this.rules = rules;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String method = exchange.getRequest().getMethod().name();
    if (!rules.audited(method, exchange.getRequest().getPath().pathWithinApplication().value())) {
      return ReactivePendingAuditFailures.boundary(chain.filter(exchange));
    }
    if (nativeTrail != null) return nativeFilter(nativeTrail, exchange, chain, method);
    AuditTrail imperative = Objects.requireNonNull(trail);
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
                  imperative
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
                LoggerFactory.getLogger(HttpAuditWebFilter.class)
                    .warn("Audit event could not be recorded", e);
              }
            });
  }

  private Mono<Void> nativeFilter(
      ReactiveAuditTrail nativeAudit,
      ServerWebExchange exchange,
      WebFilterChain chain,
      String method) {
    return Mono.deferContextual(
        ctx -> {
          InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
          AuditTrail.Context actor =
              ReactiveAuditContext.current(
                  context,
                  ctx,
                  remote != null && remote.getAddress() != null
                      ? remote.getAddress().getHostAddress()
                      : null);
          return ReactivePendingAuditFailures.boundary(
              ReactiveAudit.around(
                  Mono.defer(() -> chain.filter(exchange)),
                  ignored -> nativeRecord(nativeAudit, exchange, method, actor, null),
                  error -> nativeRecord(nativeAudit, exchange, method, actor, error)));
        });
  }

  private static Mono<Void> nativeRecord(
      ReactiveAuditTrail trail,
      ServerWebExchange exchange,
      String method,
      AuditTrail.Context actor,
      @Nullable Throwable error) {
    return Mono.defer(
        () -> {
          PathPattern pattern =
              exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
          HttpStatusCode status = exchange.getResponse().getStatusCode();
          int code = status != null ? status.value() : error == null ? 200 : 500;
          var record =
              trail
                  .record(
                      "http." + method.toLowerCase(Locale.ROOT),
                      pattern != null ? pattern.getPatternString() : "unmatched")
                  .context(actor)
                  .detail("status", code);
          if (error != null)
            return record.failure(
                error instanceof AxizException ax
                    ? ax.code().value()
                    : error.getClass().getSimpleName());
          return code >= 400 ? record.failure("HTTP-" + code) : record.success();
        });
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
