package pe.axiz.spring.security.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.adapter.security.redis.RedisAsyncRateLimiter;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.security.RateLimiter;

class RedisRateLimitWiringTest {
  private static final String[] SETTINGS = {
    "axiz.security.rate-limit.enabled=true", "axiz.security.rate-limit.store=redis"
  };

  @SuppressWarnings("unchecked")
  private LettuceConnectionFactory factory() {
    var factory = mock(LettuceConnectionFactory.class);
    var client = mock(RedisClient.class);
    StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
    when(factory.getRequiredNativeClient()).thenReturn(client);
    when(client.connect()).thenReturn(connection);
    when(connection.sync()).thenReturn(mock(RedisCommands.class));
    when(connection.async()).thenReturn(mock(RedisAsyncCommands.class));
    return factory;
  }

  @Test
  void imperativeAndReactiveApplicationsReceiveOnlyTheirOwnPort() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityRedisAutoConfiguration.class))
        .withPropertyValues(SETTINGS)
        .withBean(LettuceConnectionFactory.class, this::factory)
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(RateLimiter.class)
                  .doesNotHaveBean(AsyncRateLimiter.class);
              assertThat(context.getBean(RateLimiter.class)).isInstanceOf(RedisRateLimiter.class);
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecurityRedisAutoConfiguration.class))
        .withPropertyValues(SETTINGS)
        .withBean(LettuceConnectionFactory.class, this::factory)
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(AsyncRateLimiter.class)
                  .doesNotHaveBean(RateLimiter.class);
              assertThat(context.getBean(AsyncRateLimiter.class))
                  .isInstanceOf(RedisAsyncRateLimiter.class);
            });
  }

  @Test
  void userPortWinsAndAnUnselectedIntegrationDoesNotCreateAConnection() {
    var custom = mock(RateLimiter.class);
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizSecurityRedisAutoConfiguration.class))
            .withPropertyValues(SETTINGS)
            .withBean(LettuceConnectionFactory.class, this::factory);
    runner
        .withBean(RateLimiter.class, () -> custom)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RateLimiter.class);
              assertThat(context.getBean(RateLimiter.class)).isSameAs(custom);
            });
    runner
        .withPropertyValues("axiz.security.rate-limit.store=custom")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(RateLimiter.class)
                    .doesNotHaveBean(StatefulRedisConnection.class));
    runner
        .withPropertyValues("axiz.security.rate-limit.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(RateLimiter.class)
                    .doesNotHaveBean(StatefulRedisConnection.class));
  }
}
