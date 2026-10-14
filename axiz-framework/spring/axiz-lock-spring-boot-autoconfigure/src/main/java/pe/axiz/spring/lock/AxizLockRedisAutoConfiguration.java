package pe.axiz.spring.lock;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.redis.RedisDistributedLock;

/** Redis locks (SET NX PX with owner token, atomic release and renewal). */
@AutoConfiguration(
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.core.lock.redis.RedisDistributedLock",
      "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    })
@ConditionalOnBean(LettuceConnectionFactory.class)
@ConditionalOnProperty(name = "axiz.lock.store", havingValue = "redis")
@EnableConfigurationProperties(AxizLockProperties.class)
public class AxizLockRedisAutoConfiguration {

  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, String> axizLockRedisConnection(
      LettuceConnectionFactory factory) {
    return ((RedisClient) factory.getRequiredNativeClient()).connect();
  }

  @Bean
  DistributedLock axizRedisLock(
      @Qualifier("axizLockRedisConnection") StatefulRedisConnection<String, String> c,
      AxizLockProperties p) {
    return new RedisDistributedLock(c.sync(), p.keyPrefix());
  }
}
