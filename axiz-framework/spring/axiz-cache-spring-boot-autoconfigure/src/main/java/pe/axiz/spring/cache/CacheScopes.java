package pe.axiz.spring.cache;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;
import pe.axiz.core.cache.CacheKeys;
import pe.axiz.core.cache.CacheScope;
import pe.axiz.core.tenancy.TenantContext;

/**
 * The part of a method key that says who shares the entry: the scope, the tenant and the caller,
 * always three encoded parts so a key never reads as another one. The tenant comes from {@link
 * TenantContext} (blocking calls, or reactive calls with automatic context propagation) or from the
 * Reactor context under {@link #TENANT_KEY} (written by the tenancy starter). The caller is the
 * name of the authenticated Spring Security principal.
 */
final class CacheScopes {

  /** Reactor context key of the tenant, the same as {@code TenantContextAccessor.KEY}. */
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
   * Scope part for a blocking call.
   *
   * @return the part, or empty when the scope needs a caller and there is none (do not cache)
   */
  static Optional<String> blocking(CacheScope scope) {
    String tenant = scope == CacheScope.GLOBAL ? null : threadTenant();
    if (scope != CacheScope.CALLER) {
      return Optional.of(part(scope, tenant, null));
    }
    String caller = SECURITY ? SecurityCallers.current() : null;
    return caller == null ? Optional.empty() : Optional.of(part(scope, tenant, caller));
  }

  static String part(CacheScope scope, @Nullable String tenant, @Nullable String caller) {
    return CacheKeys.of(scope.name(), tenant, caller);
  }

  static @Nullable String threadTenant() {
    return TENANCY ? TenantContext.current().orElse(null) : null;
  }
}
