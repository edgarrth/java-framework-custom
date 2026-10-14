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
import pe.axiz.core.security.RateLimiter;
import pe.axiz.core.security.redis.RedisRateLimiter;

/**
 * Cluster-wide rate limits on ElastiCache (Redis/Valkey), using the connection settings of Boot's
 * Redis support ({@code spring.data.redis.*}, including TLS with certificate verification).
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
    before = AxizSecurityAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "pe.axiz.core.security.redis.RedisRateLimiter",
      "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    })
@ConditionalOnBean(LettuceConnectionFactory.class)
@ConditionalOnBooleanProperty(name = "axiz.security.rate-limit.enabled")
@EnableConfigurationProperties(AxizSecurityProperties.class)
public class AxizSecurityRedisAutoConfiguration {

  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, String> axizRateLimitRedisConnection(
      LettuceConnectionFactory factory) {
    return ((RedisClient) factory.getRequiredNativeClient()).connect();
  }

  @Bean
  RateLimiter axizRedisRateLimiter(
      StatefulRedisConnection<String, String> axizRateLimitRedisConnection,
      AxizSecurityProperties p) {
    return new RedisRateLimiter(
        axizRateLimitRedisConnection.sync(), "axiz:", p.rateLimit().failOpen());
  }
}
