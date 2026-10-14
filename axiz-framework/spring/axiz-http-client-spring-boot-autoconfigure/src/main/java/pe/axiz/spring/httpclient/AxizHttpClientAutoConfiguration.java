package pe.axiz.spring.httpclient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;
import pe.axiz.core.secrets.SecretResolver;

/**
 * Declarative HTTP clients ({@code @HttpExchange}) on Spring's HTTP service groups: every group
 * whose name matches {@code axiz.http-client.clients.<name>} gets the axiz transport, timeouts,
 * credentials, resilience, propagation, error translation and logging. Boot's builders are
 * customized, never replaced.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.resilience.AxizResilienceAutoConfiguration",
      "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration",
      "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration",
      "org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.http-client.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizHttpClientProperties.class)
public class AxizHttpClientAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  HttpClientRegistry axizHttpClientRegistry(
      AxizHttpClientProperties properties,
      Environment env,
      ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
      ObjectProvider<SecretResolver> secrets,
      ObjectProvider<SslBundles> ssl,
      ObjectProvider<Clock> clock) {
    String tenantHeader =
        env.getProperty("axiz.tenancy.enabled", Boolean.class, false)
            ? env.getProperty("axiz.tenancy.header", "X-Tenant-Id")
            : null;
    return new HttpClientRegistry(
        properties,
        circuitBreakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
        secrets.getIfAvailable(),
        ssl.getIfAvailable(),
        clock.getIfAvailable(Clock::systemUTC),
        tenantHeader);
  }

  /** Blocking stack: {@code RestClient} (scales with virtual threads). */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.springframework.web.client.RestClient",
        "org.apache.hc.client5.http.impl.classic.HttpClients"
      })
  static class RestClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AxizHttpClients axizHttpClients(
        HttpClientRegistry registry, ObjectProvider<RestClient.Builder> builders) {
      return new AxizHttpClients(registry, () -> builders.getIfAvailable(RestClient::builder));
    }

    @Bean
    RestClientHttpServiceGroupConfigurer axizRestClientGroups(
        HttpClientRegistry registry, AxizHttpClients clients) {
      return groups ->
          groups.forEachClient(
              (HttpServiceGroupConfigurer.ClientCallback<RestClient.Builder>)
                  (group, builder) ->
                      registry.find(group.name()).ifPresent(s -> clients.configure(s, builder)));
    }
  }

  /** Reactive stack: {@code WebClient}, never blocking. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
  static class WebClientConfiguration {

    @Bean
    WebClientHttpServiceGroupConfigurer axizWebClientGroups(HttpClientRegistry registry) {
      return groups ->
          groups.forEachClient(
              (HttpServiceGroupConfigurer.ClientCallback<WebClient.Builder>)
                  (group, builder) ->
                      registry
                          .find(group.name())
                          .ifPresent(
                              s -> {
                                HttpClientSettings transport = registry.transport(s);
                                builder
                                    .baseUrl(s.baseUrl().toString())
                                    .clientConnector(
                                        ClientHttpConnectorBuilder.detect().build(transport))
                                    .filter(
                                        new AxizExchangeFilter(
                                            registry.resilience(s),
                                            registry.auth(s),
                                            registry.tenantHeader()));
                              }));
    }
  }
}
