package pe.axiz.spring.httpclient;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import pe.axiz.core.http.ClientSettings;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the axiz settings of a logical client to a {@code RestClient.Builder}: Apache HttpClient
 * 5 transport with pool and timeouts and without its own retries, observation name, error
 * translation and the axiz interceptor. Used by the HTTP service group configurer and available for
 * programmatic clients.
 */
public final class AxizHttpClients implements AutoCloseable {
  private final Map<String, HttpComponentsClientHttpRequestFactory> transports = new HashMap<>();
  private boolean closed;

  private final HttpClientRegistry registry;
  private final Supplier<RestClient.Builder> builders;
  private final Supplier<JsonMapper> mappers;

  public AxizHttpClients(
      HttpClientRegistry registry,
      Supplier<RestClient.Builder> builders,
      Supplier<JsonMapper> mappers) {
    this.registry = Objects.requireNonNull(registry);
    this.builders = Objects.requireNonNull(builders);
    this.mappers = Objects.requireNonNull(mappers);
  }

  /** A new client for a configured name (fails fast for unknown names). */
  public RestClient restClient(String name) {
    ClientSettings s =
        registry
            .find(name)
            .orElseThrow(() -> new IllegalArgumentException("Unknown HTTP client " + name));
    RestClient.Builder b = builders.get();
    configure(s, b);
    return b.build();
  }

  public synchronized void configure(ClientSettings s, RestClient.Builder builder) {
    if (closed) throw new IllegalStateException("HTTP client factory is closed");
    JsonMapper mapper =
        CardDataClientMapper.forClient(mappers.get(), registry.revealCardData(s.name()));
    builder.configureMessageConverters(
        converters ->
            converters
                .registerDefaults()
                .withJsonConverter(new JacksonJsonHttpMessageConverter(mapper)));
    HttpComponentsClientHttpRequestFactoryBuilder factory =
        ClientHttpRequestFactoryBuilder.httpComponents()
            // retries belong to the axiz interceptor, which only repeats idempotent calls. Apache
            // HttpClient would otherwise resend any request answered with 429 or 503, POST included
            .withHttpClientCustomizer(
                http -> http.disableAutomaticRetries().disableRedirectHandling())
            .withConnectionManagerCustomizer(
                cm ->
                    cm.setMaxConnTotal(s.maxConnections()).setMaxConnPerRoute(s.maxConnections()));
    builder
        .baseUrl(s.baseUrl().toString())
        .requestFactory(
            transports.computeIfAbsent(s.name(), name -> factory.build(registry.transport(s))))
        .observationConvention(new ClientNameObservationConvention(s.name()))
        .defaultStatusHandler(HttpStatusCode::isError, new ExternalErrorHandler(s.name()))
        .requestInterceptors(
            list ->
                list.add(
                    new AxizRestClientInterceptor(
                        registry.resilience(s), registry.auth(s), registry.tenantHeader())));
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    for (var transport : transports.values()) {
      try {
        transport.destroy();
      } catch (Exception cleanup) {
        if (failure == null)
          failure = new IllegalStateException("Cannot close HTTP client transport", cleanup);
        else failure.addSuppressed(cleanup);
      }
    }
    transports.clear();
    if (failure != null) throw failure;
  }
}
