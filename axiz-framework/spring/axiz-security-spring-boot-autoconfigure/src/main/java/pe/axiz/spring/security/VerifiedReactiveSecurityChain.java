package pe.axiz.spring.security;

import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Identifica una cadena reactiva compilada, en lugar de confiar en propiedades de configuración sin
 * uso.
 */
final class VerifiedReactiveSecurityChain implements SecurityWebFilterChain {
  private final SecurityWebFilterChain delegate;

  VerifiedReactiveSecurityChain(SecurityWebFilterChain delegate) {
    this.delegate = delegate;
  }

  @Override
  public Mono<Boolean> matches(ServerWebExchange exchange) {
    return delegate.matches(exchange);
  }

  @Override
  public Flux<WebFilter> getWebFilters() {
    return delegate.getWebFilters();
  }
}
