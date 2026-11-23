package pe.axiz.core.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.lang.Strings;
import pe.axiz.core.tenancy.InvalidTenantException;
import pe.axiz.core.tenancy.TenantContext;

/**
 * Mapea los claims validados de un token a un {@link AxizPrincipal} según un {@link ClaimMapping}.
 */
public final class ClaimsMapper {

  private final ClaimMapping mapping;
  private final boolean subjectAsClient;

  /** Mapper que exige un claim de cliente; los subjects humanos nunca son clientes implícitos. */
  public ClaimsMapper(ClaimMapping mapping) {
    this(mapping, false);
  }

  /**
   * Crea un mapper.
   *
   * @param mapping rutas de los claims
   * @param subjectAsClient opción para proveedores que ponen el cliente solo en {@code sub}:
   *     también debe coincidir un marcador de máquina configurado y propiedad del emisor. Sin esa
   *     evidencia, un claim de cliente faltante siempre se rechaza, incluso con esta opción
   *     habilitada.
   */
  public ClaimsMapper(ClaimMapping mapping, boolean subjectAsClient) {
    this.mapping = Objects.requireNonNull(mapping);
    this.subjectAsClient = subjectAsClient;
  }

  /**
   * Construye el principal; se rechaza un token sin emisor o sin id de cliente.
   *
   * @param claims claims validados
   * @return el principal
   */
  public AxizPrincipal map(Map<String, Object> claims) {
    String issuer = identityString("iss", claims.get("iss"));
    String clientId = first(claims, mapping.clientId());
    String subject = first(claims, mapping.subject());
    if (issuer == null) {
      throw AccessException.unauthenticated("token without issuer");
    }
    boolean machine = isMachineToken(claims);
    if (clientId == null && subjectAsClient && machine) {
      clientId = subject;
    }
    if (clientId == null) {
      throw AccessException.unauthenticated("token without client id");
    }
    String tenant = tenant(claims);
    // los claims de identidad alimentan claves de caché, alcances de idempotencia, auditoría y
    // logs: un carácter de control en ellos podría hacer que dos llamadores parezcan iguales al
    // unir los valores
    requirePrintable("iss", issuer);
    requirePrintable("client", clientId);
    requirePrintable("sub", subject);
    requirePrintable("tenant", tenant);
    if (subject != null && !subject.equals(clientId) && machine) {
      // una cuenta de servicio con su propio id de usuario: quien llama es la aplicación, no una
      // persona
      subject = clientId;
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (String path : mapping.attributes()) {
      Object v = resolve(claims, path);
      if (v != null) {
        attributes.put(path, v);
      }
    }
    return AxizPrincipal.builder()
        .subject(subject)
        .clientId(clientId)
        .issuer(issuer)
        .audience(strings(claims.get("aud")))
        .scopes(firstSet(claims, mapping.scopes()))
        .roles(firstSet(claims, mapping.roles()))
        .tenantId(tenant)
        .attributes(attributes)
        .build();
  }

  private @Nullable String tenant(Map<String, Object> claims) {
    for (String path : mapping.tenant()) {
      ClaimValue selected = claimValue(claims, path);
      if (!selected.present()) continue;
      Object value = selected.value();
      if (!(value instanceof String text) || !TenantContext.isValid(text))
        throw new InvalidTenantException();
      return text;
    }
    return null;
  }

  private record ClaimValue(boolean present, @Nullable Object value) {}

  private static ClaimValue claimValue(Map<String, Object> claims, String path) {
    if (claims.containsKey(path)) return new ClaimValue(true, claims.get(path));
    @Nullable Object current = claims;
    for (String segment : path.split("\\.", -1)) {
      if (!(current instanceof Map<?, ?> map)) return new ClaimValue(true, null);
      if (!map.containsKey(segment)) return new ClaimValue(false, null);
      current = map.get(segment);
    }
    return new ClaimValue(true, current);
  }

  private static void requirePrintable(String claim, @Nullable String value) {
    if (value == null) {
      return;
    }
    if (!Strings.isWellFormedUnicode(value)) {
      throw AccessException.unauthenticated("token with malformed Unicode in " + claim);
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw AccessException.unauthenticated("token with a control character in " + claim);
      }
    }
  }

  /**
   * Coincide solo con valores de cadena exactos de claims propiedad del emisor configurados de
   * forma explícita.
   */
  private boolean isMachineToken(Map<String, Object> claims) {
    for (String marker : mapping.machine()) {
      int equals = marker.indexOf('=');
      if (marker.substring(equals + 1).equals(resolve(claims, marker.substring(0, equals)))) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable String first(Map<String, Object> claims, List<String> paths) {
    for (String p : paths) {
      String v = identityString(p, resolve(claims, p));
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
    for (String part : path.split("\\.", -1)) {
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

  private static @Nullable String identityString(String claim, @Nullable Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw AccessException.unauthenticated("token with a non-text identity claim: " + claim);
    }
    return text;
  }

  private static List<String> strings(@Nullable Object v) {
    if (v == null) {
      return List.of();
    }
    if (v instanceof Collection<?> c) {
      return c.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }
    String s = v.toString().strip();
    return Arrays.stream(s.split("[\\s,]+", -1)).filter(p -> !p.isEmpty()).toList();
  }
}
