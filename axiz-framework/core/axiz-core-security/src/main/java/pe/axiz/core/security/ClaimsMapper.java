package pe.axiz.core.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.AccessException;

/** Maps validated token claims to an {@link AxizPrincipal} according to a {@link ClaimMapping}. */
public final class ClaimsMapper {

  private final ClaimMapping mapping;

  public ClaimsMapper(ClaimMapping mapping) {
    this.mapping = Objects.requireNonNull(mapping);
  }

  /**
   * Builds the principal; a token without issuer or client id is rejected.
   *
   * @param claims validated claims
   * @return the principal
   */
  public AxizPrincipal map(Map<String, Object> claims) {
    String issuer = string(claims.get("iss"));
    String clientId = first(claims, mapping.clientId());
    String subject = first(claims, mapping.subject());
    if (issuer == null) {
      throw AccessException.unauthenticated("token without issuer");
    }
    if (clientId == null) {
      // some providers only include the client in sub for client-credentials tokens
      clientId = subject;
    }
    if (clientId == null) {
      throw AccessException.unauthenticated("token without client id");
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (String path : mapping.attributes()) {
      Object v = resolve(claims, path);
      if (v != null) {
        attributes.put(path, v);
      }
    }
    return new AxizPrincipal(
        subject,
        clientId,
        issuer,
        strings(claims.get("aud")),
        firstSet(claims, mapping.scopes()),
        firstSet(claims, mapping.roles()),
        first(claims, mapping.tenant()),
        attributes);
  }

  private static @Nullable String first(Map<String, Object> claims, List<String> paths) {
    for (String p : paths) {
      String v = string(resolve(claims, p));
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private static Set<String> firstSet(Map<String, Object> claims, List<String> paths) {
    for (String p : paths) {
      Object v = resolve(claims, p);
      if (v != null) {
        return new LinkedHashSet<>(strings(v));
      }
    }
    return Set.of();
  }

  static @Nullable Object resolve(Map<String, Object> claims, String path) {
    if (claims.containsKey(path)) {
      return claims.get(path);
    }
    Object current = claims;
    for (String part : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(part);
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  private static @Nullable String string(@Nullable Object v) {
    return v == null ? null : v.toString();
  }

  private static List<String> strings(@Nullable Object v) {
    if (v == null) {
      return List.of();
    }
    if (v instanceof Collection<?> c) {
      return c.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }
    String s = v.toString().strip();
    return s.isEmpty() ? List.of() : List.of(s.split("[\\s,]+"));
  }
}
