package pe.axiz.spring.cache;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

/** El invocante autenticado; solo se carga cuando Spring Security está en el classpath. */
final class SecurityCallers {

  private SecurityCallers() {}

  /** El resolvedor que se usa cuando la aplicación no declara ninguno. */
  static CacheCallerResolver defaults() {
    return new DefaultCacheCallerResolver();
  }

  static @Nullable String current(CacheCallerResolver resolver) {
    return identity(resolver, SecurityContextHolder.getContext().getAuthentication());
  }

  static Mono<String> reactive(CacheCallerResolver resolver) {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .mapNotNull(a -> identity(resolver, a));
  }

  private static @Nullable String identity(
      CacheCallerResolver resolver, @Nullable Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    String identity = resolver.identity(authentication);
    return identity == null || identity.isBlank() ? null : identity;
  }
}
