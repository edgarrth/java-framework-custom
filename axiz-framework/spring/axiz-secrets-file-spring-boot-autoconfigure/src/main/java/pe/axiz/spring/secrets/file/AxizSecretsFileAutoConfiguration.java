package pe.axiz.spring.secrets.file;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import pe.axiz.adapter.secrets.file.EnvironmentAsyncSecretResolver;
import pe.axiz.adapter.secrets.file.EnvironmentSecretResolver;
import pe.axiz.adapter.secrets.file.FileSecretResolver;
import pe.axiz.adapter.secrets.file.RefreshingFileSecretResolver;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.spring.secrets.AsyncSecretSource;
import pe.axiz.spring.secrets.AxizSecretsProperties;
import pe.axiz.spring.secrets.SecretSource;

/** Mounted files and environment sources, ahead of remote sources by explicit bean order. */
@AutoConfiguration(beforeName = "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.secrets.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizSecretsFileProperties.class, AxizSecretsProperties.class})
public class AxizSecretsFileAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnMissingBean(SecretResolver.class)
  static class SynchronousSources {
    @Bean
    @Order(0)
    SecretSource axizMountedSecretSource(AxizSecretsFileProperties p) {
      Path directory = Path.of(p.directory());
      SecretResolver resolver =
          Files.isDirectory(directory)
              ? new FileSecretResolver(directory)
              : name -> Optional.empty();
      return new SecretSource("mounted-files", resolver);
    }

    @Bean
    @Order(10)
    @ConditionalOnBooleanProperty(name = "axiz.secrets.environment", matchIfMissing = true)
    SecretSource axizEnvironmentSecretSource() {
      return new SecretSource("environment", EnvironmentSecretResolver.system());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnMissingBean(AsyncSecretResolver.class)
  static class NativeSources {
    @Bean(destroyMethod = "close")
    MountedSecretFiles axizMountedSecretFiles(
        AxizSecretsFileProperties p, AxizSecretsProperties common) {
      Duration interval =
          common.cacheTtl().compareTo(Duration.ofSeconds(1)) < 0
              ? Duration.ofSeconds(1)
              : common.cacheTtl();
      return new MountedSecretFiles(
          new RefreshingFileSecretResolver(Path.of(p.directory()), interval));
    }

    @Bean
    @Order(0)
    AsyncSecretSource axizMountedAsyncSecretSource(MountedSecretFiles files) {
      return new AsyncSecretSource("mounted-files", files.resolver, false);
    }

    @Bean
    @Order(10)
    @ConditionalOnBooleanProperty(name = "axiz.secrets.environment", matchIfMissing = true)
    AsyncSecretSource axizEnvironmentAsyncSecretSource() {
      return new AsyncSecretSource("environment", EnvironmentAsyncSecretResolver.system(), false);
    }
  }

  /** Owns the refresher without publishing an extra resolver candidate. */
  static final class MountedSecretFiles implements AutoCloseable {
    private final RefreshingFileSecretResolver resolver;

    MountedSecretFiles(RefreshingFileSecretResolver resolver) {
      this.resolver = resolver;
    }

    @Override
    public void close() {
      resolver.close();
    }
  }
}
