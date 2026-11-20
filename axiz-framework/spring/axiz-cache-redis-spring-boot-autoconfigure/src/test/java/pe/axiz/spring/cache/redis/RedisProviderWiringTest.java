package pe.axiz.spring.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.core.cache.AsyncByteCache;
import pe.axiz.core.cache.ByteCache;

class RedisProviderWiringTest {
  @Test
  @SuppressWarnings("unchecked")
  void portsMatchTheExecutionStackAndDisabledProvidersAcquireNoConnections() {
    var factory = mock(LettuceConnectionFactory.class);
    var client = mock(RedisClient.class);
    StatefulRedisConnection<String, byte[]> connection =
        mock(StatefulRedisConnection.class, RETURNS_DEEP_STUBS);
    when(factory.getRequiredNativeClient()).thenReturn(client);
    when(client.connect(any(RedisCodec.class))).thenReturn(connection);
    var configurations = AutoConfigurations.of(AxizCacheRedisAutoConfiguration.class);
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(configurations)
            .withBean(LettuceConnectionFactory.class, () -> factory)
            .withPropertyValues("axiz.cache.redis=true");
    runner
        .withPropertyValues("axiz.cache.enabled=false")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(ByteCache.class)
                  .doesNotHaveBean(AsyncByteCache.class);
              verifyNoInteractions(client);
            });
    runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ByteCache.class));
    runner
        .withBean(Clock.class, Clock::systemUTC)
        .run(context -> assertThat(context).hasNotFailed());
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(LettuceConnectionFactory.class, () -> factory)
        .withPropertyValues("axiz.cache.redis=true")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AsyncByteCache.class)
                    .doesNotHaveBean(ByteCache.class));
    verify(connection, never()).close();
  }
}
