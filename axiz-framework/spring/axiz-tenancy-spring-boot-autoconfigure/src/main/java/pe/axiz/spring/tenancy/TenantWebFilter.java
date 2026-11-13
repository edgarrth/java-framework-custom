package pe.axiz.spring.tenancy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;
import reactor.core.publisher.Mono;

/** Reactive tenant binding through the Reactor context ({@link TenantContextAccessor#KEY}). */
public final class TenantWebFilter implements WebFilter {

  private final TenantFilterSupport support;

  public TenantWebFilter(TenantFilterSupport support) {
    this.support = Objects.requireNonNull(support);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (support.skip(exchange.getRequest().getPath().pathWithinApplication().value())) {
      return chain.filter(exchange);
    }
    RequestMetadata metadata = exchange.getAttribute(RequestContext.KEY);
    Optional<String> tenant =
        support.resolve(metadata, exchange.getRequest().getHeaders()::getFirst);
    if (tenant.isEmpty()) {
      return support.required()
          ? Mono.error(
              new ValidationException(
                  List.of(FieldViolation.of("tenant", "Required", "tenant is required"))))
          : chain.filter(exchange);
    }
    String t = tenant.get();
    RequestMetadata enriched = metadata != null ? metadata.withTenant(t) : null;
    if (enriched != null) {
      exchange.getAttributes().put(RequestContext.KEY, enriched);
    }
    return chain
        .filter(exchange)
        .contextWrite(
            ctx -> {
              var c = ctx.put(TenantContextAccessor.KEY, t);
              return enriched != null ? c.put(RequestContext.KEY, enriched) : c;
            });
  }
}
