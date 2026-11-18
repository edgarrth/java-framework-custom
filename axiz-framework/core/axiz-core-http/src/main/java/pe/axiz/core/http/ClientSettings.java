package pe.axiz.core.http;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.resilience.TimeoutSettings;

/**
 * Settings of one logical HTTP client ({@code axiz.http-client.clients.<name>}).
 *
 * @param name logical name (used in metrics, traces, logs and errors)
 * @param baseUrl base URL
 * @param timeouts mandatory timeouts
 * @param auth outbound authentication
 * @param retry retry mode
 * @param maxAttempts attempts for idempotent calls
 * @param circuitBreaker circuit breaker configuration name, or null to disable
 * @param maxConcurrentCalls bulkhead limit, or 0 to disable
 * @param propagateHeaders inbound headers forwarded to this dependency (allow-list)
 * @param maxConnections connection pool size
 */
public record ClientSettings(
    String name,
    URI baseUrl,
    TimeoutSettings timeouts,
    AuthSettings auth,
    RetryMode retry,
    int maxAttempts,
    @Nullable String circuitBreaker,
    int maxConcurrentCalls,
    List<String> propagateHeaders,
    int maxConnections,
    TransportSecurityPolicy transportSecurity) {

  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");

  public ClientSettings {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Client name must be lowercase kebab-case: " + name);
    }
    Objects.requireNonNull(baseUrl, "base-url is required for client " + name);
    Objects.requireNonNull(timeouts, "timeouts are required for client " + name);
    Objects.requireNonNull(transportSecurity).validate(baseUrl, "HTTP client " + name);
    Objects.requireNonNull(auth);
    if (auth.tokenUri() != null)
      transportSecurity.validate(auth.tokenUri(), "OAuth token-uri for " + name);
    Objects.requireNonNull(retry);
    if (maxAttempts < 1 || maxAttempts > 5) {
      throw new IllegalArgumentException("max-attempts must be between 1 and 5");
    }
    propagateHeaders = List.copyOf(propagateHeaders);
    if (maxConnections < 1) {
      throw new IllegalArgumentException("max-connections must be positive");
    }
  }

  /** Retry modes. */
  public enum RetryMode {
    NONE,
    /** Retry only idempotent methods or calls with an Idempotency-Key. */
    IDEMPOTENT_ONLY
  }

  public static ClientSettings of(String name, URI baseUrl) {
    return ClientSettings.builder()
        .name(name)
        .baseUrl(baseUrl)
        .timeouts(TimeoutSettings.defaults())
        .auth(AuthSettings.none())
        .retry(RetryMode.IDEMPOTENT_ONLY)
        .maxAttempts(3)
        .circuitBreaker("default")
        .maxConcurrentCalls(0)
        .propagateHeaders(List.of())
        .maxConnections(50)
        .transportSecurity(TransportSecurityPolicy.system())
        .build();
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable String name = null;
    private @Nullable URI baseUrl = null;
    private @Nullable TimeoutSettings timeouts = null;
    private @Nullable AuthSettings auth = null;
    private @Nullable RetryMode retry = null;
    private int maxAttempts = 0;
    private @Nullable String circuitBreaker = null;
    private int maxConcurrentCalls = 0;
    private @Nullable List<String> propagateHeaders = List.of();
    private int maxConnections = 0;
    private @Nullable TransportSecurityPolicy transportSecurity = null;

    private Builder() {}

    public Builder name(String value) {
      this.name = value;
      return this;
    }

    public Builder baseUrl(URI value) {
      this.baseUrl = value;
      return this;
    }

    public Builder timeouts(TimeoutSettings value) {
      this.timeouts = value;
      return this;
    }

    public Builder auth(AuthSettings value) {
      this.auth = value;
      return this;
    }

    public Builder retry(RetryMode value) {
      this.retry = value;
      return this;
    }

    public Builder maxAttempts(int value) {
      this.maxAttempts = value;
      return this;
    }

    public Builder circuitBreaker(@Nullable String value) {
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

    public Builder transportSecurity(TransportSecurityPolicy value) {
      this.transportSecurity = value;
      return this;
    }

    public ClientSettings build() {
      return new ClientSettings(
          Objects.requireNonNull(name, "name"),
          Objects.requireNonNull(baseUrl, "baseUrl"),
          Objects.requireNonNull(timeouts, "timeouts"),
          Objects.requireNonNull(auth, "auth"),
          Objects.requireNonNull(retry, "retry"),
          maxAttempts,
          circuitBreaker,
          maxConcurrentCalls,
          Objects.requireNonNull(propagateHeaders, "propagateHeaders"),
          maxConnections,
          Objects.requireNonNull(transportSecurity, "transportSecurity"));
    }
  }
}
