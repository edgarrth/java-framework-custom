package pe.axiz.spring.lock.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;

class RedisProviderWiringTest {
  @Test
  @SuppressWarnings("unchecked")
  void portsMatchTheExecutionStackAndDisabledProvidersAcquireNoConnections() {
    var factory = mock(LettuceConnectionFactory.class);
    var client = mock(RedisClient.class);
    StatefulRedisConnection<String, String> connection =
        mock(StatefulRedisConnection.class, RETURNS_DEEP_STUBS);
    when(factory.getRequiredNativeClient()).thenReturn(client);
    when(client.connect()).thenReturn(connection);
    var configurations = AutoConfigurations.of(AxizLockRedisAutoConfiguration.class);
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(configurations)
            .withBean(LettuceConnectionFactory.class, () -> factory)
            .withPropertyValues("axiz.lock.store=redis");
    runner
        .withPropertyValues("axiz.lock.enabled=false")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(DistributedLock.class)
                  .doesNotHaveBean(AsyncDistributedLock.class);
              verifyNoInteractions(client);
            });
    runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(DistributedLock.class));
    runner
        .withBean(Clock.class, Clock::systemUTC)
        .run(context -> assertThat(context).hasNotFailed());
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(LettuceConnectionFactory.class, () -> factory)
        .withPropertyValues("axiz.lock.store=redis")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AsyncDistributedLock.class)
                    .doesNotHaveBean(DistributedLock.class));
    verify(connection, never()).close();
  }

  @Test
  void invalidPrefixesFailBeforeOpeningTheStore() {
    assertThrows(IllegalArgumentException.class, () -> new AxizLockRedisProperties(null));
    assertThrows(IllegalArgumentException.class, () -> new AxizLockRedisProperties(" "));
  }
}
