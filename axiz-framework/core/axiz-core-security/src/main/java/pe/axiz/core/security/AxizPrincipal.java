package pe.axiz.core.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Authenticated caller, built from the access token claims.
 *
 * @param subject end user id ({@code sub}); null for machine-to-machine tokens
 * @param clientId calling application ({@code azp} or {@code client_id})
 * @param issuer token issuer
 * @param audience token audiences
 * @param scopes granted scopes
 * @param roles granted roles
 * @param tenantId tenant, for multi-tenant services
 * @param attributes other mapped claims
 */
public record AxizPrincipal(
    @Nullable String subject,
    String clientId,
    String issuer,
    List<String> audience,
    Set<String> scopes,
    Set<String> roles,
    @Nullable String tenantId,
    Map<String, Object> attributes) {

  public AxizPrincipal {
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(issuer, "issuer");
    audience = List.copyOf(audience);
    scopes = Set.copyOf(scopes);
    roles = Set.copyOf(roles);
    attributes = Map.copyOf(attributes);
  }

  public boolean hasScope(String scope) {
    return scopes.contains(scope);
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /** Whether the token represents an application acting on its own (no end user). */
  public boolean isMachine() {
    return subject == null || subject.equals(clientId);
  }

  /** Name used in logs and audit: the subject, or the client for machine tokens. */
  public String name() {
    return subject != null ? subject : clientId;
  }
}
