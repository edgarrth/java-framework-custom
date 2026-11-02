package pe.axiz.core.security;

import java.util.List;
import java.util.Set;

/**
 * Which claims hold each principal attribute. Paths use dots for nested claims ({@code
 * realm_access.roles}); the first path present wins.
 *
 * @param subject subject claims
 * @param clientId client id claims
 * @param scopes scope claims (space separated string or list)
 * @param roles role claims (list or space separated string)
 * @param tenant tenant claims
 * @param attributes extra claims copied to the principal attributes
 * @param machine exact {@code claim=value} markers issued exclusively by the trusted identity
 *     provider for application tokens, never derived from editable user attributes. Such tokens use
 *     the client id as subject. The default is Microsoft Entra's {@code idtyp=app}; other providers
 *     must configure a trusted token-kind claim. Presence and prefix heuristics and standard
 *     user-profile claims are not accepted.
 */
public record ClaimMapping(
    List<String> subject,
    List<String> clientId,
    List<String> scopes,
    List<String> roles,
    List<String> tenant,
    List<String> attributes,
    List<String> machine) {

  /** Microsoft Entra application tokens; other providers configure an issuer-owned marker. */
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

  public ClaimMapping(
      List<String> subject,
      List<String> clientId,
      List<String> scopes,
      List<String> roles,
      List<String> tenant,
      List<String> attributes) {
    this(subject, clientId, scopes, roles, tenant, attributes, DEFAULT_MACHINE_MARKERS);
  }

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

  /** Defaults that work with standard OIDC providers. */
  public static ClaimMapping defaults() {
    return new ClaimMapping(
        List.of("sub"),
        List.of("azp", "client_id", "cid", "appid"),
        List.of("scope", "scp"),
        List.of("roles", "realm_access.roles", "groups"),
        List.of("tenant", "tenant_id", "tid"),
        List.of());
  }
}
