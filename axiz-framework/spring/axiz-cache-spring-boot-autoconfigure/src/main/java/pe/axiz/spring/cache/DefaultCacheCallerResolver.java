package pe.axiz.spring.cache;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.util.ClassUtils;
import pe.axiz.core.cache.CacheKeys;
import pe.axiz.core.security.AxizPrincipal;

/**
 * Identidad de los invocantes con token: emisor, subject y cliente del token de acceso. El emisor
 * separa dos dominios de confianza cuando reutilizan un subject, y el cliente separa las
 * aplicaciones a través de las cuales llama un usuario, ya que un resultado puede depender del
 * cliente (sus scopes, su canal).
 *
 * <ul>
 *   <li>un {@link AxizPrincipal} (starter de seguridad de axiz): su emisor, subject y cliente;
 *   <li>un principal JWT del resource server de Spring: {@code iss}, {@code sub} y {@code azp} o
 *       {@code client_id}; un token sin {@code iss} no se cachea.
 * </ul>
 *
 * Cualquier otra autenticación devuelve {@code null}: su nombre por sí solo no es una identidad
 * segura cuando un servicio acepta más de un tipo de invocante, por lo que la llamada se ejecuta
 * sin caché salvo que el servicio declare su propio {@link CacheCallerResolver}.
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

  /** Solo se carga cuando axiz-core-security está en el classpath. */
  private static final class Axiz {
    static @Nullable String identity(@Nullable Object principal) {
      return principal instanceof AxizPrincipal p
          ? token(p.issuer(), p.subject(), p.clientId())
          : null;
    }
  }

  /** Solo se carga cuando spring-security-oauth2-core está en el classpath. */
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
