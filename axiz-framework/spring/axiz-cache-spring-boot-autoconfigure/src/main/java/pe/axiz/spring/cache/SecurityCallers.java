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

  static @Nullable String current() {
    return name(SecurityContextHolder.getContext().getAuthentication());
  }

  static Mono<String> reactive() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .mapNotNull(SecurityCallers::name);
  }

  private static @Nullable String name(@Nullable Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    String name = authentication.getName();
    return name == null || name.isBlank() ? null : name;
  }
}
