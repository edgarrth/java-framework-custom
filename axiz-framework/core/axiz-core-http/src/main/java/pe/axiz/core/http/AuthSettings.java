package pe.axiz.core.http;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Outbound authentication settings. Secrets are referenced by name (resolved through the
 * SecretResolver), never written in configuration.
 *
 * @param type authentication type
 * @param tokenUri token endpoint for client credentials
 * @param clientId OAuth2 client id
 * @param clientSecretRef secret name of the client secret
 * @param scopes requested scopes
 * @param audience requested audience (some providers need it)
 * @param apiKeyHeader header carrying the API key
 * @param apiKeyRef secret name of the API key
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
        // nothing to validate
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

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
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
