package pe.axiz.spring.secrets;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
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
import pe.axiz.core.secrets.SecretCacheObserver;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

/**
 * Compone fuentes de secretos ordenadas con una política de caché común. Las integraciones definen
 * la selección de fuentes, la configuración del proveedor y los ciclos de vida de los recursos. Los
 * valores nunca se registran.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "axiz.secrets.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSecretsProperties.class)
public class AxizSecretsAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(SecretResolver.class)
  CachingSecretResolver axizSecretResolver(
      AxizSecretsProperties p,
      ObjectProvider<SecretSource> sources,
      ObjectProvider<Clock> clock,
      @Qualifier("axizSecretCacheSyncObserver") ObjectProvider<SecretCacheObserver> observers) {
    List<SecretResolver> chain = sources.orderedStream().map(SecretSource::resolver).toList();
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
      ManagedAsyncSources axizAsyncSecretResolver(
          AxizSecretsProperties p,
          ObjectProvider<AsyncSecretSource> sources,
          ObjectProvider<Clock> clocks,
          @Qualifier("axizSecretCacheAsyncObserver") ObjectProvider<SecretCacheObserver> observers) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        SecretCacheObserver observer = observers.getIfAvailable(() -> SecretCacheObserver.NONE);
        List<AsyncSecretResolver> chain = new ArrayList<>();
        List<AsyncSecretResolver> cached = new ArrayList<>();
        List<CachingAsyncSecretResolver> owned = new ArrayList<>();
        for (AsyncSecretSource source : sources.orderedStream().toList()) {
          if (source.cacheable()) {
            cached.add(source.resolver());
          } else {
            appendCached(chain, cached, owned, p.cacheTtl(), clock, observer);
            chain.add(source.resolver());
          }
        }
        appendCached(chain, cached, owned, p.cacheTtl(), clock, observer);
        return new ManagedAsyncSources(new CompositeAsyncSecretResolver(chain), owned);
      }

      private static void appendCached(
          List<AsyncSecretResolver> chain,
          List<AsyncSecretResolver> cached,
          List<CachingAsyncSecretResolver> owned,
          Duration ttl,
          Clock clock,
          SecretCacheObserver observer) {
        if (cached.isEmpty()) return;
        var cache =
            new CachingAsyncSecretResolver(
                new CompositeAsyncSecretResolver(List.copyOf(cached)), ttl, clock, observer);
        chain.add(cache);
        owned.add(cache);
        cached.clear();
      }
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveSecrets axizReactiveSecrets(AsyncSecretResolver resolver) {
      return new ReactiveSecrets(resolver);
    }
  }

  /**
   * Posee solo las cachés fabricadas aquí; los clientes de las fuentes siguen perteneciendo a sus
   * beans de proveedor.
   */
  static final class ManagedAsyncSources implements AsyncSecretResolver, AutoCloseable {
    private final AsyncSecretResolver delegate;
    private final List<CachingAsyncSecretResolver> owned;

    ManagedAsyncSources(AsyncSecretResolver delegate, List<CachingAsyncSecretResolver> owned) {
      this.delegate = delegate;
      this.owned = List.copyOf(owned);
    }

    @Override
    public CompletionStage<Optional<SecretValue>> resolve(String name) {
      return delegate.resolve(name);
    }

    @Override
    public void close() {
      owned.forEach(CachingAsyncSecretResolver::close);
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
}
