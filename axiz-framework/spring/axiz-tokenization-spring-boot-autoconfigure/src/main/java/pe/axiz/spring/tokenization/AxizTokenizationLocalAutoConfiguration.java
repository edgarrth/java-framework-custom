package pe.axiz.spring.tokenization;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.tokenization.AsyncTokenizer;

/**
 * Doble de prueba explícito que requiere tanto mode=local como un entorno de despliegue permitido.
 */
@AutoConfiguration(after = AxizTokenizationRemoteAutoConfiguration.class)
@ConditionalOnProperty(name = "axiz.tokenization.mode", havingValue = "local")
public class AxizTokenizationLocalAutoConfiguration {
  @Bean
  SyntheticTokenizer axizSyntheticTokenizer(Environment environment) {
    if (!Environments.allowsInMemoryState(environment.getProperty("axiz.config.environment")))
      throw new IllegalStateException("Local tokenization is restricted to local/test/ci");
    return new SyntheticTokenizer();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class Native {
    @Bean
    ReactiveTokenizer axizSyntheticReactiveTokenizer(SyntheticTokenizer tokenizer) {
      return new SyntheticReactiveTokenizer(tokenizer);
    }

    @Bean
    AsyncTokenizer axizSyntheticAsyncTokenizer(ReactiveTokenizer tokenizer) {
      return new AsyncRemoteTokenizer(tokenizer);
    }
  }
}
