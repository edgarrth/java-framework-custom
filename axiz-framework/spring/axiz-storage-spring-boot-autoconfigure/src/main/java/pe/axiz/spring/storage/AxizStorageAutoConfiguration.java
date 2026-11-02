package pe.axiz.spring.storage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.core.storage.ObjectStorage;

/** In-memory storage for tests and local runs ({@code axiz.storage.type=memory}). */
@AutoConfiguration(afterName = "pe.axiz.spring.storage.AxizStorageS3AutoConfiguration")
@EnableConfigurationProperties(AxizStorageProperties.class)
public class AxizStorageAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "axiz.storage.type", havingValue = "memory")
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  ObjectStorage axizInMemoryObjectStorage(Environment env) {
    Environments.requireSingleProcess(
        env.getProperty("axiz.config.environment"),
        "axiz.storage.type=memory",
        "s3",
        "objects kept in memory are lost on restart and invisible to other replicas");
    return new InMemoryObjectStorage();
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
              .REACTIVE)
  static class ReactiveConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "axiz.storage.type", havingValue = "memory")
    pe.axiz.core.storage.AsyncObjectStorage axizInMemoryAsyncObjectStorage(Environment env) {
      Environments.requireSingleProcess(
          env.getProperty("axiz.config.environment"),
          "axiz.storage.type=memory",
          "s3",
          "objects kept in memory are lost on restart and invisible to other replicas");
      return new pe.axiz.core.storage.InMemoryAsyncObjectStorage();
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveStorage axizReactiveStorage(pe.axiz.core.storage.AsyncObjectStorage storage) {
      return new ReactiveStorage(storage);
    }
  }
}
