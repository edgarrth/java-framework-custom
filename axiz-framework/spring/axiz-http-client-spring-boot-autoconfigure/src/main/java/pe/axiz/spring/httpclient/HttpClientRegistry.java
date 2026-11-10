package pe.axiz.spring.httpclient;

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
import org.springframework.boot.ssl.SslBundles;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretResolver;

/** Validated settings and shared per-client components (resilience, credentials, transport). */
public class HttpClientRegistry implements AutoCloseable {

  private final Map<String, ClientSettings> settings = new LinkedHashMap<>();
  private final Map<String, AxizHttpClientProperties.Client> raw;
  private final Map<String, ClientResilience> resilience = new ConcurrentHashMap<>();
  private final Map<String, OutboundAuth> auth = new ConcurrentHashMap<>();
  private final Map<String, OutboundAuth> nativeAuth = new LinkedHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final CircuitBreakerRegistry circuitBreakers;
  private final @Nullable SecretResolver secrets;
  private final @Nullable SslBundles sslBundles;
  private final AxizHttpClientProperties properties;
  private final Clock clock;
  private final @Nullable String tenantHeader;

  public HttpClientRegistry(
      AxizHttpClientProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      @Nullable SecretResolver secrets,
      @Nullable SslBundles sslBundles,
      Clock clock,
      @Nullable String tenantHeader) {
    this(
        properties,
        circuitBreakers,
        secrets,
        null,
        sslBundles,
        clock,
        tenantHeader,
        ObservationRegistry.NOOP);
  }

  public HttpClientRegistry(
      AxizHttpClientProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      @Nullable SecretResolver secrets,
      @Nullable AsyncSecretResolver asyncSecrets,
      @Nullable SslBundles sslBundles,
      Clock clock,
      @Nullable String tenantHeader) {
    this(
        properties,
        circuitBreakers,
        secrets,
        asyncSecrets,
        sslBundles,
        clock,
        tenantHeader,
        ObservationRegistry.NOOP);
  }

  public HttpClientRegistry(
      AxizHttpClientProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      @Nullable SecretResolver secrets,
      @Nullable AsyncSecretResolver asyncSecrets,
      @Nullable SslBundles sslBundles,
      Clock clock,
      @Nullable String tenantHeader,
      ObservationRegistry observations) {
    this.properties = properties;
    this.raw = properties.clients();
    this.circuitBreakers = circuitBreakers;
    this.secrets = secrets;
    this.sslBundles = sslBundles;
    this.clock = clock;
    this.tenantHeader = tenantHeader;
    // fail fast at startup on invalid client settings
    raw.forEach((name, c) -> settings.put(name, c.toSettings(name)));
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
              + " requires AsyncSecretResolver; configure native secrets or provide that core port");
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
        HttpClientSettings.defaults().withTimeouts(s.timeouts().connect(), s.timeouts().read());
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
}
