package pe.axiz.core.security;

import java.util.List;

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
 * @param machine markers of a token issued to an application without an end user (client
 *     credentials) whose {@code sub} is not the client id: {@code claim} (present), {@code
 *     claim=value} or {@code claim^=prefix} (the claim equals the prefix followed by the token's
 *     client id, ignoring case). Such tokens are mapped with the client id as subject (RFC 9068),
 *     so they are never mistaken for an end user. Defaults: Microsoft Entra ({@code idtyp=app}) and
 *     Keycloak service accounts ({@code preferred_username^=service-account-}).
 */
public record ClaimMapping(
    List<String> subject,
    List<String> clientId,
    List<String> scopes,
    List<String> roles,
    List<String> tenant,
    List<String> attributes,
    List<String> machine) {

  /** Keycloak service accounts and Microsoft Entra application tokens. */
  public static final List<String> DEFAULT_MACHINE_MARKERS =
      List.of("idtyp=app", "preferred_username^=service-account-");

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
