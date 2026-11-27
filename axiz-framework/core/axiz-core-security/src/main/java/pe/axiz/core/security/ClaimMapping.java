package pe.axiz.core.security;

import java.util.List;
import java.util.Set;

/**
 * Qué claims contienen cada atributo del principal. Las rutas usan puntos para los claims anidados
 * ({@code realm_access.roles}); prevalece la primera ruta presente.
 *
 * @param subject claims del subject
 * @param clientId claims del id de cliente
 * @param scopes claims de scopes (cadena separada por espacios o lista)
 * @param roles claims de roles (lista o cadena separada por espacios)
 * @param tenant claims del tenant
 * @param attributes claims adicionales copiados a los atributos del principal
 * @param machine marcadores exactos {@code claim=value} emitidos exclusivamente por el proveedor de
 *     identidad confiable para tokens de aplicación, nunca derivados de atributos de usuario
 *     editables. Esos tokens usan el id de cliente como subject. El valor por defecto es {@code
 *     idtyp=app} de Microsoft Entra; otros proveedores deben configurar un claim confiable de tipo
 *     de token. No se aceptan heurísticas de presencia o de prefijo ni claims estándar del perfil
 *     de usuario.
 */
public record ClaimMapping(
    List<String> subject,
    List<String> clientId,
    List<String> scopes,
    List<String> roles,
    List<String> tenant,
    List<String> attributes,
    List<String> machine) {

  /**
   * Tokens de aplicación de Microsoft Entra; otros proveedores configuran un marcador propiedad del
   * emisor.
   */
  public static final List<String> DEFAULT_MACHINE_MARKERS = List.of("idtyp=app");

  private static final Set<String> USER_PROFILE_CLAIMS =
      Set.of(
          "preferred_username",
          "username",
          "name",
          "given_name",
          "family_name",
          "middle_name",
          "nickname",
          "email",
          "profile",
          "picture",
          "website",
          "gender",
          "birthdate",
          "zoneinfo",
          "locale",
          "phone_number",
          "address",
          "updated_at");

  public ClaimMapping {
    subject = List.copyOf(subject);
    clientId = List.copyOf(clientId);
    scopes = List.copyOf(scopes);
    roles = List.copyOf(roles);
    tenant = List.copyOf(tenant);
    attributes = List.copyOf(attributes);
    machine = List.copyOf(machine);
    machine.forEach(ClaimMapping::requireMachineMarker);
  }

  private static void requireMachineMarker(String marker) {
    int equals = marker.indexOf('=');
    if (equals <= 0
        || equals == marker.length() - 1
        || !marker.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_.:/-]*")
        || marker.substring(equals + 1).isBlank()) {
      throw new IllegalArgumentException(
          "Machine markers require an exact issuer-owned claim=value");
    }
    String claim = marker.substring(0, equals);
    String leaf = claim.substring(Math.max(claim.lastIndexOf('.'), claim.lastIndexOf('/')) + 1);
    if (USER_PROFILE_CLAIMS.contains(leaf)) {
      throw new IllegalArgumentException(
          "User-profile claims cannot identify application tokens: " + claim);
    }
  }

  /** Valores por defecto que funcionan con proveedores OIDC estándar. */
  public static ClaimMapping defaults() {
    return new ClaimMapping(
        List.of("sub"),
        List.of("azp", "client_id", "cid", "appid"),
        List.of("scope", "scp"),
        List.of("roles", "realm_access.roles", "groups"),
        List.of("tenant", "tenant_id", "tid"),
        List.of(),
        ClaimMapping.DEFAULT_MACHINE_MARKERS);
  }
}
