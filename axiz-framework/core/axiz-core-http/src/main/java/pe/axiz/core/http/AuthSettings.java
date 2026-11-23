package pe.axiz.core.http;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Configuración de autenticación saliente. Los secretos se referencian por nombre (resueltos
 * mediante el SecretResolver), nunca se escriben en la configuración.
 *
 * @param type tipo de autenticación
 * @param tokenUri endpoint de tokens para client credentials
 * @param clientId id de cliente OAuth2
 * @param clientSecretRef nombre del secreto del client secret
 * @param scopes scopes solicitados
 * @param audience audiencia solicitada (algunos proveedores la necesitan)
 * @param apiKeyHeader cabecera que lleva la API key
 * @param apiKeyRef nombre del secreto de la API key
 */
public record AuthSettings(
    AuthType type,
    @Nullable URI tokenUri,
    @Nullable String clientId,
    @Nullable String clientSecretRef,
    List<String> scopes,
    @Nullable String audience,
    @Nullable String apiKeyHeader,
    @Nullable String apiKeyRef) {

  public AuthSettings {
    Objects.requireNonNull(type);
    scopes = List.copyOf(scopes);
    switch (type) {
      case CLIENT_CREDENTIALS -> {
        if (tokenUri == null || clientId == null || clientSecretRef == null) {
          throw new IllegalArgumentException(
              "client-credentials needs token-uri, client-id and client-secret-ref");
        }
      }
      case API_KEY -> {
        if (apiKeyHeader == null || apiKeyRef == null) {
          throw new IllegalArgumentException("api-key needs api-key-header and api-key-ref");
        }
      }
      default -> {
        // nada que validar
      }
    }
  }

  public static AuthSettings none() {
    return AuthSettings.builder()
        .type(AuthType.NONE)
        .tokenUri(null)
        .clientId(null)
        .clientSecretRef(null)
        .scopes(List.of())
        .audience(null)
        .apiKeyHeader(null)
        .apiKeyRef(null)
        .build();
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable AuthType type = null;
    private @Nullable URI tokenUri = null;
    private @Nullable String clientId = null;
    private @Nullable String clientSecretRef = null;
    private @Nullable List<String> scopes = List.of();
    private @Nullable String audience = null;
    private @Nullable String apiKeyHeader = null;
    private @Nullable String apiKeyRef = null;

    private Builder() {}

    public Builder type(AuthType value) {
      this.type = value;
      return this;
    }

    public Builder tokenUri(@Nullable URI value) {
      this.tokenUri = value;
      return this;
    }

    public Builder clientId(@Nullable String value) {
      this.clientId = value;
      return this;
    }

    public Builder clientSecretRef(@Nullable String value) {
      this.clientSecretRef = value;
      return this;
    }

    public Builder scopes(List<String> value) {
      this.scopes = List.copyOf(value);
      return this;
    }

    public Builder audience(@Nullable String value) {
      this.audience = value;
      return this;
    }

    public Builder apiKeyHeader(@Nullable String value) {
      this.apiKeyHeader = value;
      return this;
    }

    public Builder apiKeyRef(@Nullable String value) {
      this.apiKeyRef = value;
      return this;
    }

    public AuthSettings build() {
      return new AuthSettings(
          Objects.requireNonNull(type, "type"),
          tokenUri,
          clientId,
          clientSecretRef,
          Objects.requireNonNull(scopes, "scopes"),
          audience,
          apiKeyHeader,
          apiKeyRef);
    }
  }
}
