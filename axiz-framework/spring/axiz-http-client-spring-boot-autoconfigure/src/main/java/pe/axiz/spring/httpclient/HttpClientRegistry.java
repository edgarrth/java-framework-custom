package pe.axiz.spring.httpclient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.ssl.SslBundles;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.secrets.SecretResolver;

/** Validated settings and shared per-client components (resilience, credentials, transport). */
public class HttpClientRegistry {

  private final Map<String, ClientSettings> settings = new LinkedHashMap<>();
  private final Map<String, AxizHttpClientProperties.Client> raw;
  private final Map<String, ClientResilience> resilience = new ConcurrentHashMap<>();
  private final Map<String, OutboundAuth> auth = new ConcurrentHashMap<>();
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
    this.properties = properties;
    this.raw = properties.clients();
    this.circuitBreakers = circuitBreakers;
    this.secrets = secrets;
    this.sslBundles = sslBundles;
    this.clock = clock;
    this.tenantHeader = tenantHeader;
    // fail fast at startup on invalid client settings
    raw.forEach((name, c) -> settings.put(name, c.toSettings(name)));
  }

  public Optional<ClientSettings> find(String name) {
    return Optional.ofNullable(settings.get(name));
  }

  public ClientResilience resilience(ClientSettings s) {
    return resilience.computeIfAbsent(s.name(), n -> new ClientResilience(s, circuitBreakers));
  }

  public OutboundAuth auth(ClientSettings s) {
    return auth.computeIfAbsent(
        s.name(), n -> OutboundAuth.create(s, secrets, properties.tokenRenewBefore(), clock));
  }

  public HttpClientSettings transport(ClientSettings s) {
    HttpClientSettings t =
        HttpClientSettings.defaults().withTimeouts(s.timeouts().connect(), s.timeouts().read());
    String bundle = raw.get(s.name()).sslBundle();
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
