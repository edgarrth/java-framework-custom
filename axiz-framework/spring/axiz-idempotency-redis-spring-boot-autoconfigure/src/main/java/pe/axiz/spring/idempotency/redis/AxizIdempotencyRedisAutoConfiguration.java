package pe.axiz.spring.idempotency.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
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
import pe.axiz.adapter.idempotency.redis.RedisAsyncIdempotencyStore;
import pe.axiz.adapter.idempotency.redis.RedisIdempotencyStore;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/** ElastiCache (Redis/Valkey) idempotency store on Boot's Lettuce connection settings. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.idempotency.AxizIdempotencyAutoConfiguration",
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.idempotency.redis.RedisIdempotencyStore",
      "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    })
@ConditionalOnBean(LettuceConnectionFactory.class)
@ConditionalOnProperty(name = "axiz.idempotency.store", havingValue = "redis")
@EnableConfigurationProperties(AxizIdempotencyRedisProperties.class)
@ConditionalOnBooleanProperty(name = "axiz.idempotency.enabled", matchIfMissing = true)
public class AxizIdempotencyRedisAutoConfiguration {

  // no destroy method: the connection belongs to the factory's RedisClient, which closes it when
  // the factory stops (a lifecycle phase that runs before beans are destroyed); closing it again
  // made Lettuce warn "Connection is already closed" at every shutdown
  @Bean(destroyMethod = "")
  StatefulRedisConnection<String, String> axizIdempotencyRedisConnection(
      LettuceConnectionFactory factory) {
    return ((RedisClient) factory.getRequiredNativeClient()).connect();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  IdempotencyStore axizRedisIdempotencyStore(
      @Qualifier("axizIdempotencyRedisConnection") StatefulRedisConnection<String, String> connection,
      AxizIdempotencyRedisProperties p,
      ObjectProvider<Clock> clock) {
    return new RedisIdempotencyStore(
        connection.sync(), p.keyPrefix(), clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  AsyncIdempotencyStore axizRedisAsyncIdempotencyStore(
      @Qualifier("axizIdempotencyRedisConnection") StatefulRedisConnection<String, String> connection,
      AxizIdempotencyRedisProperties p,
      ObjectProvider<Clock> clock) {
    return new RedisAsyncIdempotencyStore(
        connection.async(), p.keyPrefix(), clock.getIfAvailable(Clock::systemUTC));
  }
}
