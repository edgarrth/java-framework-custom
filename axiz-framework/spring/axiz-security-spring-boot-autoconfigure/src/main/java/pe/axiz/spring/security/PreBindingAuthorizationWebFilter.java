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
 * Lado reactivo de {@link PreBindingAuthorization}. Se ejecuta después de la cadena de filtros de
 * seguridad, que ha establecido el invocante y traduce la denegación a la respuesta de problema, y
 * antes de que el dispatcher lea el cuerpo de la solicitud.
 */
final class PreBindingAuthorizationWebFilter implements WebFilter, Ordered {

  private final SingletonSupplier<RequestMappingHandlerMapping> mapping;
  private final PreAuthorizeReactiveAuthorizationManager manager;
  private final PreBindingAuthorization policy = new PreBindingAuthorization();

  /**
   * Crea el filtro.
   *
   * @param mapping los mapeos de solicitudes de la aplicación, resueltos en el primer uso (el
   *     filtro se crea antes que la infraestructura de WebFlux)
   * @param manager evalúa las expresiones {@code @PreAuthorize} contra el invocante reactivo
   */
  PreBindingAuthorizationWebFilter(
      Supplier<RequestMappingHandlerMapping> mapping,
      PreAuthorizeReactiveAuthorizationManager manager) {
    this.mapping = SingletonSupplier.of(mapping);
    this.manager = manager;
  }

  @Override
  public int getOrder() {
    return 0; // después del WebFilterChainProxy de Spring Security (-100)
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
