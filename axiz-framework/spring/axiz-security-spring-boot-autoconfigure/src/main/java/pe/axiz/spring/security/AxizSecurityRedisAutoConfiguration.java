package pe.axiz.spring.security;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.core.security.RateLimiter;

/**
 * Cluster-wide rate limits on ElastiCache (Redis/Valkey), using the connection settings of Boot's
 * Redis support ({@code spring.data.redis.*}, including TLS with certificate verification).
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
    before = AxizSecurityAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.security.redis.RedisRateLimiter",
      "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    })
@ConditionalOnBean(LettuceConnectionFactory.class)
@ConditionalOnBooleanProperty(name = "axiz.security.rate-limit.enabled")
@EnableConfigurationProperties(AxizSecurityProperties.class)
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
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  RateLimiter axizRedisRateLimiter(
      StatefulRedisConnection<String, String> axizRateLimitRedisConnection,
      AxizSecurityProperties p) {
    return new RedisRateLimiter(
        axizRateLimitRedisConnection.sync(), "axiz:", p.rateLimit().failOpen());
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
              .REACTIVE)
  pe.axiz.core.security.AsyncRateLimiter axizRedisAsyncRateLimiter(
      StatefulRedisConnection<String, String> axizRateLimitRedisConnection) {
    return new pe.axiz.adapter.security.redis.RedisAsyncRateLimiter(
        axizRateLimitRedisConnection.async(), "axiz:");
  }
}
