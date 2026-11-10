package pe.axiz.spring.cache;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.util.ClassUtils;
import pe.axiz.core.cache.CacheKeys;
import pe.axiz.core.security.AxizPrincipal;

/**
 * Identity of token callers: issuer, subject and client of the access token. The issuer keeps two
 * trust domains apart when they reuse a subject, and the client keeps apart the applications a user
 * calls through, since a result may depend on the client (its scopes, its channel).
 *
 * <ul>
 *   <li>an {@link AxizPrincipal} (axiz security starter): its issuer, subject and client;
 *   <li>a JWT principal of the Spring resource server: {@code iss}, {@code sub} and {@code azp} or
 *       {@code client_id}; a token without {@code iss} is not cached.
 * </ul>
 *
 * Any other authentication returns {@code null}: its name alone is not a safe identity when a
 * service accepts more than one kind of caller, so the call runs uncached unless the service
 * declares its own {@link CacheCallerResolver}.
 */
public class DefaultCacheCallerResolver implements CacheCallerResolver {

  private static final ClassLoader LOADER = DefaultCacheCallerResolver.class.getClassLoader();
  private static final boolean AXIZ =
      ClassUtils.isPresent("pe.axiz.core.security.AxizPrincipal", LOADER);
  private static final boolean CLAIMS =
      ClassUtils.isPresent("org.springframework.security.oauth2.core.ClaimAccessor", LOADER);

  @Override
  public @Nullable String identity(Authentication authentication) {
    Object principal = authentication.getPrincipal();
    if (AXIZ) {
      String axiz = Axiz.identity(principal);
      if (axiz != null) {
        return axiz;
      }
    }
    return CLAIMS ? Claims.identity(principal) : null;
  }

  static String token(String issuer, @Nullable String subject, @Nullable String client) {
    return CacheKeys.of("token", issuer, subject, client);
  }

  /** Loaded only when axiz-core-security is on the classpath. */
  private static final class Axiz {
    static @Nullable String identity(@Nullable Object principal) {
      return principal instanceof AxizPrincipal p
          ? token(p.issuer(), p.subject(), p.clientId())
          : null;
    }
  }

  /** Loaded only when spring-security-oauth2-core is on the classpath. */
  private static final class Claims {
    static @Nullable String identity(@Nullable Object principal) {
      if (!(principal instanceof ClaimAccessor claims)) {
        return null;
      }
      String issuer = claims.getClaimAsString("iss");
      if (issuer == null || issuer.isBlank()) {
        return null;
      }
      String client = claims.getClaimAsString("azp");
      return token(
          issuer,
          claims.getClaimAsString("sub"),
          client != null ? client : claims.getClaimAsString("client_id"));
    }
  }
}
