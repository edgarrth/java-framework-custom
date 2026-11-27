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

/**
 * Almacén de idempotencia en ElastiCache (Redis/Valkey) sobre la configuración de conexión Lettuce
 * de Boot.
 */
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

  // sin método destroy: la conexión pertenece al RedisClient de la fábrica, que la cierra cuando la
  // fábrica se detiene (una fase del ciclo de vida que se ejecuta antes de destruir los beans);
  // cerrarla otra vez hace que Lettuce advierta "Connection is already closed" en cada apagado
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
