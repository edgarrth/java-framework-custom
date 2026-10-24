package pe.axiz.spring.httpclient;

import java.util.Objects;
import java.util.function.Supplier;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import pe.axiz.core.http.ClientSettings;

/**
 * Applies the axiz settings of a logical client to a {@code RestClient.Builder}: Apache HttpClient
 * 5 transport with pool and timeouts and without its own retries, observation name, error
 * translation and the axiz interceptor. Used by the HTTP service group configurer and available for
 * programmatic clients.
 */
public class AxizHttpClients {

  private final HttpClientRegistry registry;
  private final Supplier<RestClient.Builder> builders;

  public AxizHttpClients(HttpClientRegistry registry, Supplier<RestClient.Builder> builders) {
    this.registry = Objects.requireNonNull(registry);
    this.builders = Objects.requireNonNull(builders);
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

  public void configure(ClientSettings s, RestClient.Builder builder) {
    HttpComponentsClientHttpRequestFactoryBuilder factory =
        ClientHttpRequestFactoryBuilder.httpComponents()
            // retries belong to the axiz interceptor, which only repeats idempotent calls. Apache
            // HttpClient would otherwise resend any request answered with 429 or 503, POST included
            .withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries)
            .withConnectionManagerCustomizer(
                cm ->
                    cm.setMaxConnTotal(s.maxConnections()).setMaxConnPerRoute(s.maxConnections()));
    builder
        .baseUrl(s.baseUrl().toString())
        .requestFactory(factory.build(registry.transport(s)))
        .observationConvention(new ClientNameObservationConvention(s.name()))
        .defaultStatusHandler(HttpStatusCode::isError, new ExternalErrorHandler(s.name()))
        .requestInterceptors(
            list ->
                list.add(
                    new AxizRestClientInterceptor(
                        registry.resilience(s), registry.auth(s), registry.tenantHeader())));
  }
}
