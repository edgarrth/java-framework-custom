package pe.axiz.spring.security.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
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
import pe.axiz.adapter.security.redis.RedisAsyncRateLimiter;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * Cluster-wide rate limits on ElastiCache (Redis/Valkey), using the connection settings of Boot's
 * Redis support ({@code spring.data.redis.*}, including TLS with certificate verification).
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
    beforeName = "pe.axiz.spring.security.AxizSecurityAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.security.redis.RedisRateLimiter",
      "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    })
@ConditionalOnProperty(name = "axiz.security.rate-limit.store", havingValue = "redis")
@ConditionalOnBean(LettuceConnectionFactory.class)
@ConditionalOnBooleanProperty(name = "axiz.security.rate-limit.enabled")
@EnableConfigurationProperties(AxizSecurityRedisProperties.class)
public class AxizSecurityRedisAutoConfiguration {

  // no destroy method: the connection belongs to the factory's RedisClient, which closes it when
  // the factory stops (a lifecycle phase that runs before beans are destroyed); closing it again
  // made Lettuce warn "Connection is already closed" at every shutdown
  @Bean(destroyMethod = "")
  StatefulRedisConnection<String, String> axizRateLimitRedisConnection(
      LettuceConnectionFactory factory) {
    return ((RedisClient) factory.getRequiredNativeClient()).connect();
  }

  @Bean
  @ConditionalOnImperativeApplication
  @ConditionalOnMissingBean(RateLimiter.class)
  RateLimiter axizRedisRateLimiter(
      StatefulRedisConnection<String, String> axizRateLimitRedisConnection,
      AxizSecurityRedisProperties p) {
    return new RedisRateLimiter(axizRateLimitRedisConnection.sync(), p.keyPrefix(), false);
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnMissingBean(AsyncRateLimiter.class)
  AsyncRateLimiter axizRedisAsyncRateLimiter(
      StatefulRedisConnection<String, String> axizRateLimitRedisConnection,
      AxizSecurityRedisProperties p) {
    return new RedisAsyncRateLimiter(axizRateLimitRedisConnection.async(), p.keyPrefix());
  }
}
