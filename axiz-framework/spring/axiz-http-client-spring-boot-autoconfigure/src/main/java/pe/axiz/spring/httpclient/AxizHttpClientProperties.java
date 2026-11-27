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
 * Clientes HTTP lógicos ({@code axiz.http-client.clients.<name>}). El nombre coincide con el grupo
 * de servicios HTTP de las interfaces {@code @HttpExchange} y etiqueta métricas, trazas, logs y
 * errores.
 *
 * @param clients clientes por nombre
 * @param tokenRenewBefore los tokens de client-credentials se renuevan cuando les queda menos que
 *     esto
 */
@ConfigurationProperties("axiz.http-client")
public record AxizHttpClientProperties(
    @DefaultValue Map<String, Client> clients, @DefaultValue("60s") Duration tokenRenewBefore) {
  public AxizHttpClientProperties {
    clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
  }

  /**
   * Un cliente.
   *
   * @param baseUrl URL base (obligatoria)
   * @param connectTimeout timeout de conexión
   * @param readTimeout timeout de respuesta
   * @param totalTimeout presupuesto que incluye los reintentos
   * @param auth autenticación saliente
   * @param retry {@code IDEMPOTENT_ONLY} o {@code NONE}
   * @param maxAttempts intentos para llamadas idempotentes (o llamadas con un Idempotency-Key)
   * @param circuitBreaker nombre de la configuración del circuit breaker; vacío lo desactiva
   * @param maxConcurrentCalls límite del bulkhead; 0 lo desactiva
   * @param propagateHeaders cabeceras del catálogo reenviadas a esta dependencia
   * @param maxConnections tamaño del pool de conexiones
   * @param sslBundle bundle SSL para confianza personalizada o TLS mutuo
   * @param revealCardData revela los valores de tarjeta solo en este cliente saliente seleccionado
   *     explícitamente
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

    /** Construcción con nombre; build comprueba los valores obligatorios y las invariantes. */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Estado mutable de construcción; se usa un builder por operación, nunca como estado
     * compartido.
     */
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
   * Autenticación saliente; los secretos se referencian por nombre.
   *
   * @param type {@code NONE}, {@code CLIENT_CREDENTIALS}, {@code TOKEN_RELAY}, {@code API_KEY} o
   *     {@code MTLS}
   * @param tokenUri endpoint de tokens
   * @param clientId id de cliente OAuth2
   * @param clientSecretRef nombre del secreto del client secret
   * @param scopes scopes
   * @param audience audiencia
   * @param apiKeyHeader cabecera de la API key
   * @param apiKeyRef nombre del secreto de la API key
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

    /** Construcción con nombre; build comprueba los valores obligatorios y las invariantes. */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Estado mutable de construcción; se usa un builder por operación, nunca como estado
     * compartido.
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
