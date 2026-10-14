package pe.axiz.core.http;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
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
 * @param logBodies whether request and response bodies are logged (masked)
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
    boolean logBodies,
    int maxConnections) {

  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");

  public ClientSettings {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Client name must be lowercase kebab-case: " + name);
    }
    Objects.requireNonNull(baseUrl, "base-url is required for client " + name);
    Objects.requireNonNull(timeouts, "timeouts are required for client " + name);
    Objects.requireNonNull(auth);
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
    return new ClientSettings(
        name,
        baseUrl,
        TimeoutSettings.defaults(),
        AuthSettings.none(),
        RetryMode.IDEMPOTENT_ONLY,
        3,
        "default",
        0,
        List.of(),
        false,
        50);
  }
}
