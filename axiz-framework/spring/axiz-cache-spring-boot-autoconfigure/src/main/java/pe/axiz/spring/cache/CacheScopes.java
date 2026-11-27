package pe.axiz.spring.cache;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;
import pe.axiz.core.cache.CacheKeys;
import pe.axiz.core.cache.CacheScope;
import pe.axiz.core.tenancy.TenantContext;

/**
 * Parte de la clave de un método que indica quién comparte la entrada: el ámbito, el tenant y el
 * invocante, siempre tres partes codificadas para que una clave nunca se lea como otra. El tenant
 * proviene de {@link TenantContext} (llamadas bloqueantes o llamadas reactivas con propagación
 * automática del contexto) o del contexto de Reactor bajo {@link #TENANT_KEY} (escrito por el
 * starter de tenancy). El invocante es la identidad que un {@link CacheCallerResolver} asigna al
 * principal autenticado de Spring Security (emisor, subject y cliente de un token por defecto).
 */
final class CacheScopes {

  /** Clave del tenant en el contexto de Reactor, la misma que {@code TenantContextAccessor.KEY}. */
  static final String TENANT_KEY = "axiz.tenant";

  private static final ClassLoader LOADER = CacheScopes.class.getClassLoader();
  private static final boolean TENANCY =
      ClassUtils.isPresent("pe.axiz.core.tenancy.TenantContext", LOADER);
  private static final boolean SECURITY =
      ClassUtils.isPresent(
          "org.springframework.security.core.context.SecurityContextHolder", LOADER);
  static final boolean REACTIVE_SECURITY =
      SECURITY
          && ClassUtils.isPresent(
              "org.springframework.security.core.context.ReactiveSecurityContextHolder", LOADER);

  private CacheScopes() {}

  /**
   * Parte del ámbito para una llamada bloqueante.
   *
   * @return la parte, o vacío cuando el ámbito necesita un invocante y no lo hay (no se cachea)
   */
  static Optional<String> blocking(CacheScope scope, @Nullable CacheCallerResolver callers) {
    String tenant = scope == CacheScope.GLOBAL ? null : threadTenant();
    if (scope != CacheScope.CALLER) {
      return Optional.of(part(scope, tenant, null));
    }
    String caller = SECURITY && callers != null ? SecurityCallers.current(callers) : null;
    return caller == null ? Optional.empty() : Optional.of(part(scope, tenant, caller));
  }

  static String part(CacheScope scope, @Nullable String tenant, @Nullable String caller) {
    return CacheKeys.of(scope.name(), tenant, caller);
  }

  /**
   * El resolvedor por defecto, o null sin Spring Security (no puede distinguirse ningún invocante).
   */
  static @Nullable CacheCallerResolver defaultCallers() {
    return SECURITY ? SecurityCallers.defaults() : null;
  }

  static @Nullable String threadTenant() {
    return TENANCY ? TenantContext.current().orElse(null) : null;
  }
}
