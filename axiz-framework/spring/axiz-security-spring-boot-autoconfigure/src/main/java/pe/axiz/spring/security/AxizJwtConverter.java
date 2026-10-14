package pe.axiz.spring.security;

import java.util.List;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import pe.axiz.core.security.AuthorityMapper;
import pe.axiz.core.security.AxizPrincipal;
import pe.axiz.core.security.ClaimsMapper;

/** Maps a validated JWT to an {@link AxizAuthentication} with scope and role authorities. */
public class AxizJwtConverter implements Converter<Jwt, AxizAuthentication> {

  private final ClaimsMapper mapper;

  public AxizJwtConverter(ClaimsMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public AxizAuthentication convert(Jwt jwt) {
    AxizPrincipal principal = mapper.map(jwt.getClaims());
    List<SimpleGrantedAuthority> authorities =
        AuthorityMapper.authorities(principal).stream().map(SimpleGrantedAuthority::new).toList();
    return new AxizAuthentication(jwt, principal, authorities);
  }
}
