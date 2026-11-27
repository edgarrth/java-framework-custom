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
 * Límites de tasa a nivel de clúster sobre ElastiCache (Redis/Valkey), usando la configuración de
 * conexión del soporte Redis de Boot ({@code spring.data.redis.*}, incluido TLS con verificación de
 * certificados).
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

  // sin método destroy: la conexión pertenece al RedisClient de la fábrica, que la cierra cuando la
  // fábrica se detiene (una fase del ciclo de vida que se ejecuta antes de destruir los beans);
  // cerrarla otra vez hace que Lettuce advierta "Connection is already closed" en cada apagado
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
