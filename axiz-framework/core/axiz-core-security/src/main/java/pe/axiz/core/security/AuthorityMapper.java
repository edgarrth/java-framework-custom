package pe.axiz.core.security;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Mapea scopes y roles a nombres de autoridad ({@code SCOPE_x}, {@code ROLE_X}). */
public final class AuthorityMapper {

  private AuthorityMapper() {}

  public static Set<String> authorities(AxizPrincipal principal) {
    Set<String> out = new LinkedHashSet<>();
    principal.scopes().forEach(s -> out.add("SCOPE_" + s));
    principal.roles().forEach(r -> out.add("ROLE_" + r.toUpperCase(Locale.ROOT).replace(' ', '_')));
    return out;
  }
}
