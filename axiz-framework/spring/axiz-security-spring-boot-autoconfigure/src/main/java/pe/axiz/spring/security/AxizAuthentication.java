package pe.axiz.spring.security;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import pe.axiz.core.security.AxizPrincipal;

/**
 * Autenticación respaldada por un JWT validado cuyo principal es un {@link AxizPrincipal}, de modo
 * que los controladores pueden declarar {@code @AuthenticationPrincipal AxizPrincipal caller}.
 */
public final class AxizAuthentication extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

  private static final long serialVersionUID = 1L;

  private final AxizPrincipal axizPrincipal;

  public AxizAuthentication(
      Jwt jwt, AxizPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
    super(jwt, principal, jwt, authorities);
    this.axizPrincipal = principal;
    setAuthenticated(true);
  }

  public AxizPrincipal axizPrincipal() {
    return axizPrincipal;
  }

  @Override
  public Map<String, Object> getTokenAttributes() {
    return getToken().getClaims();
  }

  @Override
  public String getName() {
    return axizPrincipal.name();
  }
}
