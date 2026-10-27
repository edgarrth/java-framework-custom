package pe.axiz.spring.cache;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

/** The authenticated caller, only loaded when Spring Security is on the classpath. */
final class SecurityCallers {

  private SecurityCallers() {}

  /** The resolver used when the application declares none. */
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
