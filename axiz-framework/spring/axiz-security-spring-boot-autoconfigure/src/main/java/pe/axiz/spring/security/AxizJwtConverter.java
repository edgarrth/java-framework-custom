package pe.axiz.spring.security;

import java.util.List;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.security.AuthorityMapper;
import pe.axiz.core.security.AxizPrincipal;
import pe.axiz.core.security.ClaimsMapper;

/** Asocia un JWT validado a un {@link AxizAuthentication} con autoridades de scope y de rol. */
public final class AxizJwtConverter implements Converter<Jwt, AxizAuthentication> {

  private final ClaimsMapper mapper;

  public AxizJwtConverter(ClaimsMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public AxizAuthentication convert(Jwt jwt) {
    AxizPrincipal principal;
    try {
      principal = mapper.map(jwt.getClaims());
    } catch (AccessException invalid) {
      throw new InvalidBearerTokenException("Invalid identity claims", invalid);
    }
    List<SimpleGrantedAuthority> authorities =
        AuthorityMapper.authorities(principal).stream().map(SimpleGrantedAuthority::new).toList();
    return new AxizAuthentication(jwt, principal, authorities);
  }
}
