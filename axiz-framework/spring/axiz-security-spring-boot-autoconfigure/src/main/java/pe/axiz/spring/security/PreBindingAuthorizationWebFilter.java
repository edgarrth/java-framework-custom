package pe.axiz.spring.security;

import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.PreAuthorizeReactiveAuthorizationManager;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive side of {@link PreBindingAuthorization}. It runs after the security filter chain, which
 * has established the caller and translates the denial into the problem response, and before the
 * dispatcher reads the request body.
 */
final class PreBindingAuthorizationWebFilter implements WebFilter, Ordered {

  private final SingletonSupplier<RequestMappingHandlerMapping> mapping;
  private final PreAuthorizeReactiveAuthorizationManager manager;
  private final PreBindingAuthorization policy = new PreBindingAuthorization();

  /**
   * Creates the filter.
   *
   * @param mapping the application's request mappings, resolved on first use (the filter is created
   *     before the WebFlux infrastructure)
   * @param manager evaluates {@code @PreAuthorize} expressions against the reactive caller
   */
  PreBindingAuthorizationWebFilter(
      Supplier<RequestMappingHandlerMapping> mapping,
      PreAuthorizeReactiveAuthorizationManager manager) {
    this.mapping = SingletonSupplier.of(mapping);
    this.manager = manager;
  }

  @Override
  public int getOrder() {
    return 0; // after Spring Security's WebFilterChainProxy (-100)
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (CorsUtils.isPreFlightRequest(exchange.getRequest())) return chain.filter(exchange);
    return mapping
        .obtain()
        .getHandler(exchange)
        .flatMap(
            handler ->
                handler instanceof HandlerMethod method && policy.appliesTo(method)
                    ? manager
                        .authorize(
                            ReactiveSecurityContextHolder.getContext()
                                .mapNotNull(SecurityContext::getAuthentication),
                            PreBindingAuthorization.invocation(method))
                        .filter(result -> !result.isGranted())
                    : Mono.<AuthorizationResult>empty())
        .flatMap(
            denied -> Mono.<Void>error(new AuthorizationDeniedException("Access Denied", denied)))
        .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
  }
}
