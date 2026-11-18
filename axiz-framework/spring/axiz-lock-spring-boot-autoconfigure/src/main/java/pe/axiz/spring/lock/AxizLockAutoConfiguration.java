package pe.axiz.spring.lock;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.InMemoryAsyncDistributedLock;
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockObserver;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/** Leased locks with renewal ({@link LockTemplate}) on Redis or DynamoDB. */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "axiz.lock.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizLockProperties.class)
public class AxizLockAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  DistributedLock axizInMemoryLock(
      AxizLockProperties p, ObjectProvider<Clock> clock, Environment env) {
    if (!"memory".equals(p.store())) {
      throw new IllegalStateException(
          "axiz.lock.store="
              + p.store()
              + " is not available: add its client and connection settings");
    }
    Environments.requireSingleProcess(
        env.getProperty("axiz.config.environment"),
        "axiz.lock.store=memory",
        "redis or dynamodb",
        "a lock held in one replica does not exclude the others");
    // allowed only where one process runs (local, test): the check above fails anywhere else
    LoggerFactory.getLogger(AxizLockAutoConfiguration.class)
        .info("Locks are kept in memory (single process); replicas need redis or dynamodb");
    return new InMemoryDistributedLock(clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  LockTemplate axizLockTemplate(DistributedLock lock, AxizLockProperties p, LockObserver observer) {
    return new LockTemplate(lock, p.pollInterval(), observer);
  }

  @Bean
  @ConditionalOnMissingBean
  LockObserver axizLockObserver(ObjectProvider<MeterRegistry> meters) {
    return (resource, failure) ->
        meters.ifAvailable(registry -> registry.counter("axiz.lock.release.failures").increment());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AsyncDistributedLock axizInMemoryAsyncLock(
        AxizLockProperties p, ObjectProvider<Clock> clock, Environment env) {
      if (!"memory".equals(p.store()))
        throw new IllegalStateException("Async lock store unavailable: " + p.store());
      Environments.requireSingleProcess(
          env.getProperty("axiz.config.environment"),
          "axiz.lock.store=memory",
          "redis or dynamodb",
          "replicas require a shared lock store");
      return new InMemoryAsyncDistributedLock(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveLocks axizReactiveLocks(AsyncDistributedLock lock, AxizLockProperties p) {
      return new ReactiveLocks(lock, p.pollInterval());
    }
  }
}
