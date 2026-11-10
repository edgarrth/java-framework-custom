package pe.axiz.spring.httpclient;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.resilience.TimeoutSettings;

/**
 * Logical HTTP clients ({@code axiz.http-client.clients.<name>}). The name matches the HTTP service
 * group of the {@code @HttpExchange} interfaces and labels metrics, traces, logs and errors.
 *
 * @param clients clients by name
 * @param tokenRenewBefore client-credentials tokens are renewed when less than this remains
 */
@ConfigurationProperties("axiz.http-client")
public record AxizHttpClientProperties(
    @DefaultValue Map<String, Client> clients, @DefaultValue("60s") Duration tokenRenewBefore) {
  public AxizHttpClientProperties {
    clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
  }

  /**
   * One client.
   *
   * @param baseUrl base URL (required)
   * @param connectTimeout connection timeout
   * @param readTimeout response timeout
   * @param totalTimeout budget including retries
   * @param auth outbound authentication
   * @param retry {@code IDEMPOTENT_ONLY} or {@code NONE}
   * @param maxAttempts attempts for idempotent calls (or calls with an Idempotency-Key)
   * @param circuitBreaker circuit breaker configuration name; empty disables it
   * @param maxConcurrentCalls bulkhead limit; 0 disables it
   * @param propagateHeaders catalog headers forwarded to this dependency
   * @param logBodies whether bodies are logged (masked, truncated)
   * @param maxConnections connection pool size
   * @param sslBundle SSL bundle for custom trust or mutual TLS
   */
  public record Client(
      URI baseUrl,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("5s") Duration readTimeout,
      @DefaultValue("15s") Duration totalTimeout,
      @DefaultValue Auth auth,
      @DefaultValue("IDEMPOTENT_ONLY") ClientSettings.RetryMode retry,
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("default") String circuitBreaker,
      @DefaultValue("0") int maxConcurrentCalls,
      @DefaultValue List<String> propagateHeaders,
      @DefaultValue("false") boolean logBodies,
      @DefaultValue("50") int maxConnections,
      @Nullable String sslBundle) {
    public Client {
      propagateHeaders = List.copyOf(propagateHeaders);
    }

    public ClientSettings toSettings(String name) {
      return new ClientSettings(
          name,
          baseUrl,
          new TimeoutSettings(connectTimeout, readTimeout, totalTimeout),
          auth.toSettings(),
          retry,
          maxAttempts,
          circuitBreaker.isBlank() ? null : circuitBreaker,
          maxConcurrentCalls,
          propagateHeaders,
          logBodies,
          maxConnections);
    }
  }

  /**
   * Outbound authentication; secrets are referenced by name.
   *
   * @param type {@code NONE}, {@code CLIENT_CREDENTIALS}, {@code TOKEN_RELAY}, {@code API_KEY} or
   *     {@code MTLS}
   * @param tokenUri token endpoint
   * @param clientId OAuth2 client id
   * @param clientSecretRef secret name of the client secret
   * @param scopes scopes
   * @param audience audience
   * @param apiKeyHeader API key header
   * @param apiKeyRef secret name of the API key
   */
  public record Auth(
      @DefaultValue("NONE") AuthType type,
      @Nullable URI tokenUri,
      @Nullable String clientId,
      @Nullable String clientSecretRef,
      @DefaultValue List<String> scopes,
      @Nullable String audience,
      @Nullable String apiKeyHeader,
      @Nullable String apiKeyRef) {
    public Auth {
      scopes = List.copyOf(scopes);
    }

    public AuthSettings toSettings() {
      return new AuthSettings(
          type, tokenUri, clientId, clientSecretRef, scopes, audience, apiKeyHeader, apiKeyRef);
    }
  }
}
