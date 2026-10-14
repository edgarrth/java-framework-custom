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
 */
public record ClaimMapping(
    List<String> subject,
    List<String> clientId,
    List<String> scopes,
    List<String> roles,
    List<String> tenant,
    List<String> attributes) {

  public ClaimMapping {
    subject = List.copyOf(subject);
    clientId = List.copyOf(clientId);
    scopes = List.copyOf(scopes);
    roles = List.copyOf(roles);
    tenant = List.copyOf(tenant);
    attributes = List.copyOf(attributes);
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
