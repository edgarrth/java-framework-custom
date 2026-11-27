package pe.axiz.spring.tokenization;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.tokenization.AsyncTokenizer;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.httpclient.AxizHttpClients;
import pe.axiz.spring.httpclient.AxizWebClients;
import pe.axiz.spring.httpclient.HttpClientRegistry;

/**
 * La composición del consumidor no depende del vault, de un proveedor criptográfico ni de un perfil
 * de tarjeta.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.httpclient.AxizHttpClientAutoConfiguration")
@EnableConfigurationProperties(AxizTokenizationProperties.class)
public class AxizTokenizationRemoteAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "axiz.tokenization.mode", havingValue = "remote")
  static class Remote {
    @Bean
    ClientSettings axizTokenRemoteSettings(
        AxizTokenizationProperties properties, HttpClientRegistry registry) {
      return RemoteTokenSettings.validate(properties, registry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnImperativeApplication
    static class Imperative {
      @Bean
      Tokenizer axizRemoteTokenizer(
          AxizTokenizationProperties properties,
          HttpClientRegistry registry,
          AxizHttpClients clients) {
        var settings = RemoteTokenSettings.validate(properties, registry);
        return new RemoteTokenizer(
            clients.restClient(settings.name()), settings.timeouts().total());
      }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class Native {
      @Bean
      ReactiveTokenizer axizReactiveTokenizer(
          AxizTokenizationProperties properties,
          HttpClientRegistry registry,
          AxizWebClients clients) {
        var settings = RemoteTokenSettings.validate(properties, registry);
        return new ReactiveRemoteTokenizer(
            clients.exchangeClient(settings.name()), settings.timeouts().total());
      }

      @Bean
      @ConditionalOnProperty(name = "axiz.tokenization.remote.async-facade", havingValue = "true")
      AsyncTokenizer axizAsyncRemoteTokenizer(ReactiveTokenizer tokenizer) {
        return new AsyncRemoteTokenizer(tokenizer);
      }
    }
  }
}
