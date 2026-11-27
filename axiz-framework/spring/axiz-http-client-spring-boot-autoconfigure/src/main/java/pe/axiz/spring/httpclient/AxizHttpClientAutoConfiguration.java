package pe.axiz.spring.httpclient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * Clientes HTTP declarativos ({@code @HttpExchange}) sobre los grupos de servicios HTTP de Spring:
 * cada grupo cuyo nombre coincide con {@code axiz.http-client.clients.<name>} recibe el transporte,
 * los timeouts, las credenciales, la resiliencia, la propagación, la traducción de errores y el
 * logging de axiz. Los builders de Boot se personalizan, nunca se reemplazan. Cada cliente se
 * observa con el {@link ObservationRegistry} de la aplicación, de modo que sus spans y el {@code
 * traceparent} que envía nunca dependen de cuál de los módulos cliente de Boot ({@code
 * spring-boot-restclient}, {@code spring-boot-webclient}) esté presente.
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
      ObjectProvider<AsyncSecretResolver> asyncSecrets,
      ObjectProvider<ObservationRegistry> observations,
      ObjectProvider<SslBundles> ssl,
      ObjectProvider<Clock> clock) {
    String tenantHeader =
        env.getProperty("axiz.tenancy.enabled", Boolean.class, false)
            ? env.getProperty("axiz.tenancy.header", "X-Tenant-Id")
            : null;
    return HttpClientRegistry.builder()
        .properties(properties)
        .circuitBreakers(circuitBreakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults))
        .secrets(secrets.getIfAvailable())
        .asyncSecrets(asyncSecrets.getIfAvailable())
        .sslBundles(ssl.getIfAvailable())
        .clock(clock.getIfAvailable(Clock::systemUTC))
        .tenantHeader(tenantHeader)
        .observations(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
        .transportSecurity(TransportSecurityPolicy.from(env::getProperty))
        .build();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class NativeCredentialValidation {
    @Bean
    SmartInitializingSingleton axizNativeHttpCredentialsReady(HttpClientRegistry registry) {
      return () -> registry.all().values().forEach(registry::asyncAuth);
    }
  }

  /** Pila bloqueante: {@code RestClient} (escala con hilos virtuales). */
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
        HttpClientRegistry registry,
        ObjectProvider<RestClient.Builder> builders,
        ObjectProvider<JsonMapper> mappers,
        ObjectProvider<ObservationRegistry> observations) {
      return new AxizHttpClients(
          registry,
          () ->
              builders
                  .getIfAvailable(RestClient::builder)
                  .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP)),
          () -> mappers.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build()));
    }

    @Bean
    RestClientHttpServiceGroupConfigurer axizRestClientGroups(
        HttpClientRegistry registry,
        AxizHttpClients clients,
        ObjectProvider<ObservationRegistry> observations) {
      return groups ->
          groups.forEachClient(
              (HttpServiceGroupConfigurer.ClientCallback<RestClient.Builder>)
                  (group, builder) ->
                      registry
                          .find(group.name())
                          .ifPresent(
                              s -> {
                                builder.observationRegistry(
                                    observations.getIfAvailable(() -> ObservationRegistry.NOOP));
                                clients.configure(s, builder);
                              }));
    }
  }

  /** Pila reactiva: {@code WebClient}, nunca bloqueante. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.springframework.web.reactive.function.client.WebClient",
        "reactor.netty.http.client.HttpClient"
      })
  static class WebClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AxizWebClients axizWebClients(
        HttpClientRegistry registry,
        ObjectProvider<WebClient.Builder> builders,
        ObjectProvider<JsonMapper> mappers,
        ObjectProvider<ObservationRegistry> observations) {
      return new AxizWebClients(
          registry,
          () ->
              builders
                  .getIfAvailable(WebClient::builder)
                  .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP)),
          () -> mappers.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build()));
    }

    @Bean
    WebClientHttpServiceGroupConfigurer axizWebClientGroups(
        HttpClientRegistry registry,
        AxizWebClients clients,
        ObjectProvider<ObservationRegistry> observations) {
      return groups ->
          groups.forEachClient(
              (HttpServiceGroupConfigurer.ClientCallback<WebClient.Builder>)
                  (group, builder) ->
                      registry
                          .find(group.name())
                          .ifPresent(
                              settings -> {
                                builder.observationRegistry(
                                    observations.getIfAvailable(() -> ObservationRegistry.NOOP));
                                clients.configure(settings, builder);
                              }));
    }
  }
}
