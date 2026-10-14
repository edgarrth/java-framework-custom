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
    return new AuthSettings(AuthType.NONE, null, null, null, List.of(), null, null, null);
  }
}
