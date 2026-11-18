package pe.axiz.spring.lock.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.adapter.lock.redis.RedisAsyncDistributedLock;
import pe.axiz.adapter.lock.redis.RedisDistributedLock;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/** Redis locks (SET NX PX with owner token, atomic release and renewal). */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.lock.AxizLockAutoConfiguration",
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.lock.redis.RedisDistributedLock",
      "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    })
@ConditionalOnBean(LettuceConnectionFactory.class)
@ConditionalOnProperty(name = "axiz.lock.store", havingValue = "redis")
@EnableConfigurationProperties(AxizLockRedisProperties.class)
@ConditionalOnBooleanProperty(name = "axiz.lock.enabled", matchIfMissing = true)
public class AxizLockRedisAutoConfiguration {

  // no destroy method: the connection belongs to the factory's RedisClient, which closes it when
  // the factory stops (a lifecycle phase that runs before beans are destroyed); closing it again
  // made Lettuce warn "Connection is already closed" at every shutdown
  @Bean(destroyMethod = "")
  StatefulRedisConnection<String, String> axizLockRedisConnection(
      LettuceConnectionFactory factory) {
    return ((RedisClient) factory.getRequiredNativeClient()).connect();
  }

  @Bean
  @ConditionalOnImperativeApplication
  @ConditionalOnMissingBean(DistributedLock.class)
  DistributedLock axizRedisLock(
      @Qualifier("axizLockRedisConnection") StatefulRedisConnection<String, String> c,
      AxizLockRedisProperties p) {
    return new RedisDistributedLock(c.sync(), p.keyPrefix());
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnMissingBean(AsyncDistributedLock.class)
  AsyncDistributedLock axizRedisAsyncLock(
      @Qualifier("axizLockRedisConnection") StatefulRedisConnection<String, String> connection,
      AxizLockRedisProperties p) {
    return new RedisAsyncDistributedLock(connection.async(), p.keyPrefix());
  }
}
