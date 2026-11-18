package pe.axiz.spring.security;

import java.util.Optional;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.security.AxizPrincipal;
import reactor.core.publisher.Mono;

/** Reactive counterpart of {@link CallerContextFilter}: enriches the Reactor context. */
public class CallerContextWebFilter implements WebFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return ReactiveSecurityContextHolder.getContext()
        // a context without authentication (anonymous) is empty here, not a null element
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(AxizAuthentication.class::isInstance)
        .map(a -> Optional.of(((AxizAuthentication) a).axizPrincipal()))
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            principal -> {
              RequestMetadata current = exchange.getAttribute(RequestContext.KEY);
              if (principal.isEmpty() || current == null) {
                return chain.filter(exchange);
              }
              RequestMetadata enriched = enrich(current, principal.get());
              exchange.getAttributes().put(RequestContext.KEY, enriched);
              return chain.filter(exchange).contextWrite(c -> c.put(RequestContext.KEY, enriched));
            });
  }

  private static RequestMetadata enrich(RequestMetadata current, AxizPrincipal p) {
    return current.withCaller(p.clientId(), p.subject(), p.tenantId(), p.issuer());
  }
}
