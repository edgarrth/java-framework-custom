package pe.axiz.spring.secrets;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.CachingAsyncSecretResolver;
import pe.axiz.core.secrets.CachingSecretResolver;
import pe.axiz.core.secrets.CompositeAsyncSecretResolver;
import pe.axiz.core.secrets.CompositeSecretResolver;
import pe.axiz.core.secrets.EnvironmentAsyncSecretResolver;
import pe.axiz.core.secrets.EnvironmentSecretResolver;
import pe.axiz.core.secrets.FileSecretResolver;
import pe.axiz.core.secrets.RefreshingFileSecretResolver;
import pe.axiz.core.secrets.SecretCacheObserver;
import pe.axiz.core.secrets.SecretResolver;

/**
 * {@link SecretResolver} over mounted files, environment variables and the AWS adapters, with a TTL
 * cache. Values are never logged; AWS identity selection is owned by AwsIdentity.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.secrets.AxizSecretsAwsAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.secrets.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSecretsProperties.class)
public class AxizSecretsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  SecretResolver axizSecretResolver(
      AxizSecretsProperties p,
      ObjectProvider<SecretSource> sources,
      ObjectProvider<Clock> clock,
      @Qualifier("axizSecretCacheSyncObserver") ObjectProvider<SecretCacheObserver> observers) {
    List<SecretResolver> chain = new ArrayList<>();
    Path dir = Path.of(p.directory());
    if (Files.isDirectory(dir)) {
      chain.add(new FileSecretResolver(dir));
    }
    if (p.environment()) {
      chain.add(EnvironmentSecretResolver.system());
    }
    sources.orderedStream().map(SecretSource::resolver).forEach(chain::add);
    return new CachingSecretResolver(
        new CompositeSecretResolver(chain),
        p.cacheTtl(),
        clock.getIfAvailable(Clock::systemUTC),
        observers.getIfAvailable(() -> SecretCacheObserver.NONE));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(AsyncSecretResolver.class)
    static class NativeConfiguration {
      @Bean(destroyMethod = "close")
      MountedSecretFiles axizMountedSecretFiles(AxizSecretsProperties p) {
        return new MountedSecretFiles(p);
      }

      @Bean
      AsyncSecretResolver axizAsyncSecretResolver(
          MountedSecretFiles files,
          AxizSecretsProperties p,
          ObjectProvider<AsyncSecretSource> sources,
          ObjectProvider<Clock> clock,
          @Qualifier("axizSecretCacheAsyncObserver") ObjectProvider<SecretCacheObserver> observers) {
        List<AsyncSecretResolver> chain = new ArrayList<>();
        chain.add(files.resolver);
        if (p.environment()) chain.add(EnvironmentAsyncSecretResolver.system());
        // Local snapshots are already refreshed. Cache only remote sources to avoid doubling
        // the time during which a rotated mounted secret could remain stale.
        chain.add(
            new CachingAsyncSecretResolver(
                new CompositeAsyncSecretResolver(
                    sources.orderedStream().map(AsyncSecretSource::resolver).toList()),
                p.cacheTtl(),
                clock.getIfAvailable(Clock::systemUTC),
                observers.getIfAvailable(() -> SecretCacheObserver.NONE)));
        return new CompositeAsyncSecretResolver(chain);
      }
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveSecrets axizReactiveSecrets(AsyncSecretResolver resolver) {
      return new ReactiveSecrets(resolver);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class MetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "axizSecretCacheSyncObserver")
    SecretCacheObserver axizSecretCacheSyncObserver(ObjectProvider<MeterRegistry> registries) {
      var registry = registries.getIfAvailable();
      return registry == null ? SecretCacheObserver.NONE : new SecretCacheMetrics(registry, "sync");
    }

    @Bean
    @ConditionalOnMissingBean(name = "axizSecretCacheAsyncObserver")
    SecretCacheObserver axizSecretCacheAsyncObserver(ObjectProvider<MeterRegistry> registries) {
      var registry = registries.getIfAvailable();
      return registry == null
          ? SecretCacheObserver.NONE
          : new SecretCacheMetrics(registry, "async");
    }
  }

  /** Owns the file refresher without registering a second AsyncSecretResolver candidate. */
  static final class MountedSecretFiles implements AutoCloseable {
    private final RefreshingFileSecretResolver resolver;

    MountedSecretFiles(AxizSecretsProperties p) {
      Duration interval =
          p.cacheTtl().compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : p.cacheTtl();
      resolver = new RefreshingFileSecretResolver(Path.of(p.directory()), interval);
    }

    @Override
    public void close() {
      resolver.close();
    }
  }
}
