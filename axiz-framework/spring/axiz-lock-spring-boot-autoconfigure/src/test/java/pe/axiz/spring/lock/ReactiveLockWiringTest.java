package pe.axiz.spring.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.lock.dynamodb.DynamoDbAsyncDistributedLock;
import pe.axiz.adapter.lock.redis.RedisAsyncDistributedLock;
import pe.axiz.adapter.lock.redis.RedisDistributedLock;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Real auto-configuration selects native ports and fails closed when no shared store exists. */
class ReactiveLockWiringTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizLockAutoConfiguration.class,
                AxizLockDynamoDbAutoConfiguration.class,
                AxizLockRedisAutoConfiguration.class));
  }

  @Test
  void reactiveMemoryIsOnlyForSingleProcessAndPublishesNoBlockingFacade() {
    reactive()
        .withPropertyValues("axiz.config.environment=test")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveLocks.class)
                  .hasSingleBean(AsyncDistributedLock.class)
                  .doesNotHaveBean(DistributedLock.class)
                  .doesNotHaveBean(LockTemplate.class);
              assertThat(
                      context
                          .getBean(ReactiveLocks.class)
                          .withLock(
                              "account:1",
                              Duration.ofSeconds(1),
                              Duration.ZERO,
                              () -> Mono.just("ok"))
                          .block(Duration.ofSeconds(3)))
                  .isEqualTo("ok");
            });
    reactive()
        .withPropertyValues("axiz.config.environment=prod")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.lock.store=memory"));
    reactive()
        .withPropertyValues("axiz.lock.store=redis")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("Async lock store unavailable"));
    reactive()
        .withPropertyValues("axiz.lock.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(ReactiveLocks.class)
                    .doesNotHaveBean(AsyncDistributedLock.class));
  }

  @Test
  void providedAsyncClientIsReusedWithoutStartingAnotherCredentialOwner() {
    var client = mock(DynamoDbAsyncClient.class);
    reactive()
        .withBean(DynamoDbAsyncClient.class, () -> client)
        .withPropertyValues("axiz.lock.store=dynamodb", "axiz.lock.table=payment-locks")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveLocks.class)
                  .doesNotHaveBean(DynamoDbClient.class)
                  .doesNotHaveBean(AsyncAwsClients.class);
              assertThat(context.getBean(AsyncDistributedLock.class))
                  .isInstanceOf(DynamoDbAsyncDistributedLock.class);
            });
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void frameworkOwnsAndClosesTheDefaultAsyncClientAtStartup() {
    // Test-only credentials resolve locally; no AWS request is made.
    TestPropertyValues.of("aws.accessKeyId=local-test", "aws.secretAccessKey=local-test")
        .applyToSystemProperties(
            () ->
                reactive()
                    .withPropertyValues("axiz.lock.store=dynamodb", "axiz.aws.region=us-east-1")
                    .run(
                        context -> {
                          assertThat(context)
                              .hasNotFailed()
                              .hasSingleBean(DynamoDbAsyncClient.class)
                              .hasSingleBean(AsyncAwsClients.class)
                              .hasSingleBean(ReactiveLocks.class)
                              .doesNotHaveBean(DynamoDbClient.class);
                        }));
  }

  @Test
  @SuppressWarnings("unchecked")
  void redisSelectsOnlyTheNativeApiForEachStack() {
    var factory = mock(LettuceConnectionFactory.class);
    var client = mock(RedisClient.class);
    StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
    when(factory.getRequiredNativeClient()).thenReturn(client);
    when(client.connect()).thenReturn(connection);
    when(connection.async()).thenReturn(mock(RedisAsyncCommands.class));
    when(connection.sync()).thenReturn(mock(RedisCommands.class));
    reactive()
        .withBean(LettuceConnectionFactory.class, () -> factory)
        .withPropertyValues("axiz.lock.store=redis")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveLocks.class)
                  .doesNotHaveBean(DistributedLock.class);
              assertThat(context.getBean(AsyncDistributedLock.class))
                  .isInstanceOf(RedisAsyncDistributedLock.class);
              verify(connection, never()).sync();
            });
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizLockAutoConfiguration.class, AxizLockRedisAutoConfiguration.class))
        .withBean(LettuceConnectionFactory.class, () -> factory)
        .withPropertyValues("axiz.lock.store=redis")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(LockTemplate.class)
                  .doesNotHaveBean(AsyncDistributedLock.class);
              assertThat(context.getBean(DistributedLock.class))
                  .isInstanceOf(RedisDistributedLock.class);
            });
  }
}
