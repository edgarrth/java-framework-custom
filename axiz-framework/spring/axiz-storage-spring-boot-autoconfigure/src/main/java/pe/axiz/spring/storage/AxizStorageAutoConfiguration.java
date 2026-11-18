package pe.axiz.spring.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.storage.AsyncObjectStorage;
import pe.axiz.core.storage.InMemoryAsyncObjectStorage;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/** In-memory storage for tests and local runs ({@code axiz.storage.type=memory}). */
@AutoConfiguration
@EnableConfigurationProperties(AxizStorageProperties.class)
public class AxizStorageAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(name = "axiz.storage.type", havingValue = "memory")
  @ConditionalOnImperativeApplication
  ObjectStorage axizInMemoryObjectStorage(Environment env) {
    Environments.requireSingleProcess(
        env.getProperty("axiz.config.environment"),
        "axiz.storage.type=memory",
        "s3",
        "objects kept in memory are lost on restart and invisible to other replicas");
    return new InMemoryObjectStorage();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnImperativeApplication
  static class ProviderValidation {
    @Bean
    SmartInitializingSingleton axizStorageProviderCheck(ObjectProvider<ObjectStorage> providers) {
      return () -> {
        if (providers.getIfAvailable() == null)
          throw new IllegalStateException(
              "Storage requires its selected integration or an ObjectStorage bean");
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "axiz.storage.type", havingValue = "memory")
    AsyncObjectStorage axizInMemoryAsyncObjectStorage(Environment env) {
      Environments.requireSingleProcess(
          env.getProperty("axiz.config.environment"),
          "axiz.storage.type=memory",
          "s3",
          "objects kept in memory are lost on restart and invisible to other replicas");
      return new InMemoryAsyncObjectStorage();
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveStorage axizReactiveStorage(AsyncObjectStorage storage) {
      return new ReactiveStorage(storage);
    }
  }
}
