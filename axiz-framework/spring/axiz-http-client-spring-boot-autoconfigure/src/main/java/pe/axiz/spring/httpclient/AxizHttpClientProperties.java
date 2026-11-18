package pe.axiz.spring.httpclient;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
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
   * @param maxConnections connection pool size
   * @param sslBundle SSL bundle for custom trust or mutual TLS
   * @param revealCardData reveal card values only in this explicitly selected outbound client
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
      @DefaultValue("50") int maxConnections,
      @Nullable String sslBundle,
      @DefaultValue("false") boolean revealCardData) {
    public Client {
      propagateHeaders = List.copyOf(propagateHeaders);
    }

    public ClientSettings toSettings(String name, TransportSecurityPolicy transportSecurity) {
      return ClientSettings.builder()
          .name(name)
          .baseUrl(baseUrl)
          .timeouts(new TimeoutSettings(connectTimeout, readTimeout, totalTimeout))
          .auth(auth.toSettings())
          .retry(retry)
          .maxAttempts(maxAttempts)
          .circuitBreaker(circuitBreaker.isBlank() ? null : circuitBreaker)
          .maxConcurrentCalls(maxConcurrentCalls)
          .propagateHeaders(propagateHeaders)
          .maxConnections(maxConnections)
          .transportSecurity(transportSecurity)
          .build();
    }

    /** Named construction; required values and invariants are checked by build. */
    public static Builder builder() {
      return new Builder();
    }

    /** Mutable construction state; use one builder per operation, never as shared state. */
    public static final class Builder {
      private @Nullable URI baseUrl = null;
      private @Nullable Duration connectTimeout = null;
      private @Nullable Duration readTimeout = null;
      private @Nullable Duration totalTimeout = null;
      private @Nullable Auth auth = null;
      private ClientSettings.@Nullable RetryMode retry = null;
      private int maxAttempts = 0;
      private @Nullable String circuitBreaker = null;
      private int maxConcurrentCalls = 0;
      private @Nullable List<String> propagateHeaders = List.of();
      private int maxConnections = 0;
      private @Nullable String sslBundle = null;
      private boolean revealCardData = false;

      private Builder() {}

      public Builder baseUrl(URI value) {
        this.baseUrl = value;
        return this;
      }

      public Builder connectTimeout(Duration value) {
        this.connectTimeout = value;
        return this;
      }

      public Builder readTimeout(Duration value) {
        this.readTimeout = value;
        return this;
      }

      public Builder totalTimeout(Duration value) {
        this.totalTimeout = value;
        return this;
      }

      public Builder auth(Auth value) {
        this.auth = value;
        return this;
      }

      public Builder retry(ClientSettings.RetryMode value) {
        this.retry = value;
        return this;
      }

      public Builder maxAttempts(int value) {
        this.maxAttempts = value;
        return this;
      }

      public Builder circuitBreaker(String value) {
        this.circuitBreaker = value;
        return this;
      }

      public Builder maxConcurrentCalls(int value) {
        this.maxConcurrentCalls = value;
        return this;
      }

      public Builder propagateHeaders(List<String> value) {
        this.propagateHeaders = List.copyOf(value);
        return this;
      }

      public Builder maxConnections(int value) {
        this.maxConnections = value;
        return this;
      }

      public Builder sslBundle(@Nullable String value) {
        this.sslBundle = value;
        return this;
      }

      public Builder revealCardData(boolean value) {
        this.revealCardData = value;
        return this;
      }

      public Client build() {
        return new Client(
            Objects.requireNonNull(baseUrl, "baseUrl"),
            Objects.requireNonNull(connectTimeout, "connectTimeout"),
            Objects.requireNonNull(readTimeout, "readTimeout"),
            Objects.requireNonNull(totalTimeout, "totalTimeout"),
            Objects.requireNonNull(auth, "auth"),
            Objects.requireNonNull(retry, "retry"),
            maxAttempts,
            Objects.requireNonNull(circuitBreaker, "circuitBreaker"),
            maxConcurrentCalls,
            Objects.requireNonNull(propagateHeaders, "propagateHeaders"),
            maxConnections,
            sslBundle,
            revealCardData);
      }
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
      return AuthSettings.builder()
          .type(type)
          .tokenUri(tokenUri)
          .clientId(clientId)
          .clientSecretRef(clientSecretRef)
          .scopes(scopes)
          .audience(audience)
          .apiKeyHeader(apiKeyHeader)
          .apiKeyRef(apiKeyRef)
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

      public Auth build() {
        return new Auth(
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
}
