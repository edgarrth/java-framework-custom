package pe.axiz.spring.httpclient;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.ssl.SslBundles;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretResolver;

/** Validated settings and shared per-client components (resilience, credentials, transport). */
public final class HttpClientRegistry implements AutoCloseable {

  private final Map<String, ClientSettings> settings = new LinkedHashMap<>();
  private final Map<String, AxizHttpClientProperties.Client> raw;
  private final Map<String, ClientResilience> resilience = new ConcurrentHashMap<>();
  private final Map<String, OutboundAuth> auth = new ConcurrentHashMap<>();
  private final Map<String, OutboundAuth> nativeAuth = new LinkedHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final CircuitBreakerRegistry circuitBreakers;
  private final @Nullable SecretResolver secrets;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SslBundles is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final @Nullable SslBundles sslBundles;

  private final AxizHttpClientProperties properties;
  private final Clock clock;
  private final @Nullable String tenantHeader;

  private HttpClientRegistry(Builder builder) {
    AxizHttpClientProperties properties = Objects.requireNonNull(builder.properties, "properties");
    CircuitBreakerRegistry circuitBreakers =
        Objects.requireNonNull(builder.circuitBreakers, "circuitBreakers");
    @Nullable SecretResolver secrets = builder.secrets;
    @Nullable AsyncSecretResolver asyncSecrets = builder.asyncSecrets;
    @Nullable SslBundles sslBundles = builder.sslBundles;
    Clock clock = Objects.requireNonNull(builder.clock, "clock");
    @Nullable String tenantHeader = builder.tenantHeader;
    ObservationRegistry observations = Objects.requireNonNull(builder.observations, "observations");
    TransportSecurityPolicy transportSecurity =
        Objects.requireNonNull(builder.transportSecurity, "transportSecurity");
    this.properties = properties;
    this.raw = properties.clients();
    this.circuitBreakers = circuitBreakers;
    this.secrets = secrets;
    this.sslBundles = sslBundles;
    this.clock = clock;
    this.tenantHeader = tenantHeader;
    // fail fast at startup on invalid client settings
    raw.forEach((name, c) -> settings.put(name, c.toSettings(name, transportSecurity)));
    try {
      settings.forEach(
          (name, client) -> {
            boolean needsSecret =
                client.auth().type() == AuthType.CLIENT_CREDENTIALS
                    || client.auth().type() == AuthType.API_KEY;
            if (!needsSecret || asyncSecrets != null)
              nativeAuth.put(
                  name,
                  OutboundAuth.createAsync(
                      client,
                      asyncSecrets,
                      properties.tokenRenewBefore(),
                      clock,
                      observations,
                      client.auth().type() == AuthType.CLIENT_CREDENTIALS
                          ? transport(client).sslBundle()
                          : null));
          });
    } catch (RuntimeException | Error failure) {
      nativeAuth.values().forEach(OutboundAuth::close);
      throw failure;
    }
  }

  public boolean revealCardData(String name) {
    return Objects.requireNonNull(raw.get(name), "Unknown HTTP client").revealCardData();
  }

  public Optional<ClientSettings> find(String name) {
    return Optional.ofNullable(settings.get(name));
  }

  public ClientResilience resilience(ClientSettings s) {
    return resilience.computeIfAbsent(s.name(), n -> new ClientResilience(s, circuitBreakers));
  }

  public OutboundAuth auth(ClientSettings s) {
    if (closed.get()) throw new IllegalStateException("HTTP client registry is closed");
    return auth.computeIfAbsent(
        s.name(), n -> OutboundAuth.create(s, secrets, properties.tokenRenewBefore(), clock));
  }

  /** Returns startup-built native credentials; no client construction occurs on a request. */
  public OutboundAuth asyncAuth(ClientSettings settings) {
    if (closed.get()) throw new IllegalStateException("HTTP client registry is closed");
    var prepared = nativeAuth.get(settings.name());
    if (prepared == null)
      throw new IllegalStateException(
          "Native HTTP client "
              + settings.name()
              + " requires AsyncSecretResolver; configure native secrets or provide that core"
              + " port");
    return prepared;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    nativeAuth.values().forEach(OutboundAuth::close);
    auth.values().forEach(OutboundAuth::close);
  }

  public HttpClientSettings transport(ClientSettings s) {
    HttpClientSettings t =
        HttpClientSettings.defaults()
            .withRedirects(HttpRedirects.DONT_FOLLOW)
            .withTimeouts(s.timeouts().connect(), s.timeouts().read());
    String bundle =
        Objects.requireNonNull(raw.get(s.name()), "Unknown HTTP client " + s.name()).sslBundle();
    if (bundle != null) {
      if (sslBundles == null) {
        throw new IllegalStateException(
            "Client "
                + s.name()
                + " uses ssl-bundle "
                + bundle
                + " but no SSL bundles are configured");
      }
      t = t.withSslBundle(sslBundles.getBundle(bundle));
    }
    return t;
  }

  public @Nullable String tenantHeader() {
    return tenantHeader;
  }

  public Map<String, ClientSettings> all() {
    return Map.copyOf(settings);
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable AxizHttpClientProperties properties = null;
    private @Nullable CircuitBreakerRegistry circuitBreakers = null;
    private @Nullable SecretResolver secrets = null;
    private @Nullable AsyncSecretResolver asyncSecrets = null;
    private @Nullable SslBundles sslBundles = null;
    private @Nullable Clock clock = null;
    private @Nullable String tenantHeader = null;
    private @Nullable ObservationRegistry observations = null;
    private @Nullable TransportSecurityPolicy transportSecurity = null;

    private Builder() {}

    public Builder properties(AxizHttpClientProperties value) {
      this.properties = value;
      return this;
    }

    public Builder circuitBreakers(CircuitBreakerRegistry value) {
      this.circuitBreakers = value;
      return this;
    }

    public Builder secrets(@Nullable SecretResolver value) {
      this.secrets = value;
      return this;
    }

    public Builder asyncSecrets(@Nullable AsyncSecretResolver value) {
      this.asyncSecrets = value;
      return this;
    }

    public Builder sslBundles(@Nullable SslBundles value) {
      this.sslBundles = value;
      return this;
    }

    public Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    public Builder tenantHeader(@Nullable String value) {
      this.tenantHeader = value;
      return this;
    }

    public Builder observations(ObservationRegistry value) {
      this.observations = value;
      return this;
    }

    public Builder transportSecurity(TransportSecurityPolicy value) {
      this.transportSecurity = value;
      return this;
    }

    public HttpClientRegistry build() {
      return new HttpClientRegistry(this);
    }
  }
}
