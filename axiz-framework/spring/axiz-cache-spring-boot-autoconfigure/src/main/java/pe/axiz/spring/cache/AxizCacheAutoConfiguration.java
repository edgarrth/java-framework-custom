package pe.axiz.spring.cache;

import java.time.Clock;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import pe.axiz.core.cache.AxizCacheable;
import pe.axiz.core.cache.ByteCache;
import pe.axiz.core.cache.InMemoryByteCache;
import pe.axiz.core.cache.TwoLevelCache;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AxizCacheable} and named caches on L1 (Caffeine) plus L2 (ElastiCache) when configured.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
      "pe.axiz.spring.cache.AxizCacheRedisAutoConfiguration",
      "pe.axiz.spring.cache.AxizCacheCaffeineAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.cache.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizCacheProperties.class)
public class AxizCacheAutoConfiguration {

  @Bean(name = "axizL1Cache")
  @ConditionalOnMissingBean(name = "axizL1Cache")
  pe.axiz.core.cache.LocalByteCache axizDefaultL1Cache(
      AxizCacheProperties p, ObjectProvider<Clock> clock) {
    return new InMemoryByteCache(p.l1().maxEntries(), clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  AxizCaches axizCaches(
      AxizCacheProperties p,
      Environment env,
      @Qualifier("axizL1Cache") ObjectProvider<ByteCache> local,
      @Qualifier("axizL2Cache") ObjectProvider<ByteCache> l2,
      ObjectProvider<JsonMapper> mapper,
      ObjectProvider<Clock> clock) {
    ByteCache l1 =
        local.getIfAvailable(
            () ->
                new InMemoryByteCache(p.l1().maxEntries(), clock.getIfAvailable(Clock::systemUTC)));
    ByteCache remote = p.redis() ? l2.getIfAvailable() : null;
    ByteCache store = remote != null ? new TwoLevelCache(l1, remote, p.l1().maxTtl()) : l1;
    String configuredPrefix = p.prefix();
    String prefix =
        configuredPrefix != null
            ? configuredPrefix
            : env.getProperty("spring.application.name", "application");
    JsonMapper json = mapper.getIfAvailable();
    return new AxizCaches(
        prefix,
        store,
        new JacksonValueCodec(
            json != null ? json : JsonMapper.builder().findAndAddModules().build()),
        p);
  }

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor axizCacheableAdvisor(
      ObjectProvider<AxizCaches> caches,
      ObjectProvider<JsonMapper> mapper,
      ObjectProvider<CacheCallerResolver> callers,
      ObjectProvider<AsyncCacheSettings> asyncCaches) {
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, AxizCacheable.class, true),
            new AxizCacheableInterceptor(
                caches::getObject,
                () -> mapper.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build()),
                () -> callers.getIfAvailable(CacheScopes::defaultCallers),
                asyncCaches::getObject));
    advisor.setOrder(AxizCacheableInterceptor.ORDER);
    return advisor;
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
      name = "reactor.core.publisher.Mono")
  static class ReactiveConfiguration {
    @Bean
    @org.springframework.context.annotation.Lazy
    @ConditionalOnMissingBean(AsyncCacheSettings.class)
    ReactiveCaches axizReactiveCaches(
        AxizCacheProperties p,
        Environment env,
        @Qualifier("axizL1Cache") ObjectProvider<pe.axiz.core.cache.LocalByteCache> local,
        @Qualifier("axizAsyncL2Cache") ObjectProvider<pe.axiz.core.cache.AsyncByteCache> remote,
        ObjectProvider<Clock> clock) {
      pe.axiz.core.cache.LocalByteCache l1 =
          local.getIfAvailable(
              () ->
                  new InMemoryByteCache(
                      p.l1().maxEntries(), clock.getIfAvailable(Clock::systemUTC)));
      pe.axiz.core.cache.AsyncByteCache l2 = p.redis() ? remote.getIfAvailable() : null;
      String prefix =
          p.prefix() != null
              ? p.prefix()
              : env.getProperty("spring.application.name", "application");
      return new ReactiveCaches(
          prefix, new pe.axiz.core.cache.AsyncTwoLevelCache(l1, l2, p.l1().maxTtl()), p);
    }
  }
}
