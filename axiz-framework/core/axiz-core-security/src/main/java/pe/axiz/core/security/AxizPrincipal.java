package pe.axiz.core.security;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Authenticated caller, built from the access token claims. It is serializable because Spring
 * Security keeps the authentication in the HTTP session when the service uses one; the claims it
 * copies are serializable values, as in the token itself.
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
    Map<String, Object> attributes)
    implements Serializable {

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

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable String subject = null;
    private @Nullable String clientId = null;
    private @Nullable String issuer = null;
    private @Nullable List<String> audience = List.of();
    private @Nullable Set<String> scopes = Set.of();
    private @Nullable Set<String> roles = Set.of();
    private @Nullable String tenantId = null;
    private @Nullable Map<String, Object> attributes = Map.of();

    private Builder() {}

    public Builder subject(@Nullable String value) {
      this.subject = value;
      return this;
    }

    public Builder clientId(String value) {
      this.clientId = value;
      return this;
    }

    public Builder issuer(String value) {
      this.issuer = value;
      return this;
    }

    public Builder audience(List<String> value) {
      this.audience = List.copyOf(value);
      return this;
    }

    public Builder scopes(Set<String> value) {
      this.scopes = Set.copyOf(value);
      return this;
    }

    public Builder roles(Set<String> value) {
      this.roles = Set.copyOf(value);
      return this;
    }

    public Builder tenantId(@Nullable String value) {
      this.tenantId = value;
      return this;
    }

    public Builder attributes(Map<String, Object> value) {
      this.attributes = Map.copyOf(value);
      return this;
    }

    public AxizPrincipal build() {
      return new AxizPrincipal(
          subject,
          Objects.requireNonNull(clientId, "clientId"),
          Objects.requireNonNull(issuer, "issuer"),
          Objects.requireNonNull(audience, "audience"),
          Objects.requireNonNull(scopes, "scopes"),
          Objects.requireNonNull(roles, "roles"),
          tenantId,
          Objects.requireNonNull(attributes, "attributes"));
    }
  }
}
