package pe.axiz.spring.lock;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockTemplate;

/** Leased locks with renewal ({@link LockTemplate}) on Redis or DynamoDB. */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.lock.AxizLockRedisAutoConfiguration",
      "pe.axiz.spring.lock.AxizLockDynamoDbAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.lock.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizLockProperties.class)
public class AxizLockAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
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
    org.slf4j.LoggerFactory.getLogger(AxizLockAutoConfiguration.class)
        .info("Locks are kept in memory (single process); replicas need redis or dynamodb");
    return new InMemoryDistributedLock(clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  LockTemplate axizLockTemplate(DistributedLock lock, AxizLockProperties p) {
    return new LockTemplate(lock, p.pollInterval());
  }
}
