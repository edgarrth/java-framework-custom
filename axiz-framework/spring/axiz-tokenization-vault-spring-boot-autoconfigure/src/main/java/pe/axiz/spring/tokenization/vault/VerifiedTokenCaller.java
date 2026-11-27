package pe.axiz.spring.tokenization.vault;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.vault.TokenCaller;

/**
 * Asocia un JWT de resource server ya verificado; nunca lee cabeceras, cuerpos ni estado
 * ThreadLocal.
 */
public final class VerifiedTokenCaller {
  private final AxizTokenizationVaultProperties.Auth auth;

  public VerifiedTokenCaller(AxizTokenizationVaultProperties.Auth auth) {
    this.auth = Objects.requireNonNull(auth);
  }

  public TokenCaller resolve(Authentication authentication) {
    if (!(authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> token)
        || !(token.getToken() instanceof Jwt jwt)
        || !authentication.isAuthenticated()) throw TokenErrors.failure(TokenErrors.DENIED);
    try {
      var claims = jwt.getClaims();
      String subject = stringClaim(claims.get("sub"));
      String client = stringClaim(claims.get(auth.clientClaim()));
      String tenant =
          auth.fixedTenant() != null
              ? auth.fixedTenant()
              : stringClaim(claims.get(auth.tenantClaim()));
      if (!(claims.get(auth.domainsClaim()) instanceof Collection<?> entries)
          || entries.isEmpty()
          || entries.size() > 64) throw TokenErrors.failure(TokenErrors.DENIED);
      Set<TokenDomain> domains = new HashSet<>();
      for (Object value : entries) {
        if (!domains.add(new TokenDomain(stringClaim(value))))
          throw TokenErrors.failure(TokenErrors.DENIED);
      }
      var authorities = authentication.getAuthorities();
      if (authorities.isEmpty() || authorities.size() > 128)
        throw TokenErrors.failure(TokenErrors.DENIED);
      Set<String> permissions = new HashSet<>();
      authorities.forEach(value -> permissions.add(value.getAuthority()));
      return new TokenCaller(subject, client, tenant, permissions, domains);
    } catch (RuntimeException invalidClaim) {
      throw TokenErrors.failure(TokenErrors.DENIED);
    }
  }

  private static String stringClaim(@Nullable Object value) {
    if (!(value instanceof String text)) throw TokenErrors.failure(TokenErrors.DENIED);
    return text;
  }
}
