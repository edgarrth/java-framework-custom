package pe.axiz.spring.cache.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.adapter.cache.redis.RedisAsyncByteCache;
import pe.axiz.adapter.cache.redis.RedisByteCache;
import pe.axiz.core.cache.AsyncByteCache;
import pe.axiz.core.cache.ByteCache;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;

/**
 * L2 cache on ElastiCache using Boot's Redis connection settings ({@code spring.data.redis.*}, TLS
 * with certificate verification). Keys are removed with SCAN, never KEYS.
 */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.cache.AxizCacheAutoConfiguration",
    afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.cache.redis.RedisByteCache",
      "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"
    })
@ConditionalOnBean(LettuceConnectionFactory.class)
@ConditionalOnBooleanProperty(
    name = {"axiz.cache.enabled", "axiz.cache.redis"},
    matchIfMissing = true)
@EnableConfigurationProperties(AxizCacheRedisProperties.class)
public class AxizCacheRedisAutoConfiguration {

  // no destroy method: the connection belongs to the factory's RedisClient, which closes it when
  // the factory stops (a lifecycle phase that runs before beans are destroyed); closing it again
  // made Lettuce warn "Connection is already closed" at every shutdown
  @Bean(destroyMethod = "")
  StatefulRedisConnection<String, byte[]> axizCacheRedisConnection(
      LettuceConnectionFactory factory) {
    return ((RedisClient) factory.getRequiredNativeClient())
        .connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
  }

  @Bean
  @ConditionalOnImperativeApplication
  @ConditionalOnMissingBean(name = "axizL2Cache")
  ByteCache axizL2Cache(
      @Qualifier("axizCacheRedisConnection") StatefulRedisConnection<String, byte[]> connection) {
    return new RedisByteCache(connection.sync());
  }

  @Bean
  @ConditionalOnMissingBean(name = "axizAsyncL2Cache")
  AsyncByteCache axizAsyncL2Cache(
      @Qualifier("axizCacheRedisConnection") StatefulRedisConnection<String, byte[]> connection) {
    return new RedisAsyncByteCache(connection.async());
  }
}
