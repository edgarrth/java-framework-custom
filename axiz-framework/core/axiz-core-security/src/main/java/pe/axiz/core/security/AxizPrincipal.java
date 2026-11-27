package pe.axiz.core.security;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Llamador autenticado, construido a partir de los claims del token de acceso. Es serializable
 * porque Spring Security conserva la autenticación en la sesión HTTP cuando el servicio usa una;
 * los claims que copia son valores serializables, como en el propio token.
 *
 * @param subject id del usuario final ({@code sub}); null para tokens máquina a máquina
 * @param clientId aplicación que llama ({@code azp} o {@code client_id})
 * @param issuer emisor del token
 * @param audience audiencias del token
 * @param scopes scopes concedidos
 * @param roles roles concedidos
 * @param tenantId tenant, para servicios multi-tenant
 * @param attributes otros claims mapeados
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

  /** Indica si el token representa a una aplicación que actúa por sí misma (sin usuario final). */
  public boolean isMachine() {
    return subject == null || subject.equals(clientId);
  }

  /** Nombre usado en logs y auditoría: el subject o, para tokens de máquina, el cliente. */
  public String name() {
    return subject != null ? subject : clientId;
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
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
