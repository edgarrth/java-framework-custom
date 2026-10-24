package pe.axiz.spring.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.lock.dynamodb.DynamoDbDistributedLock;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** The lock store is chosen explicitly; a lock that cannot exclude other replicas is refused. */
class LockStoreSelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizLockRedisAutoConfiguration.class,
                  AxizLockDynamoDbAutoConfiguration.class,
                  AxizLockAutoConfiguration.class));

  @Test
  void anInMemoryLockIsRefusedWhereSeveralReplicasRun() {
    runner
        .withPropertyValues("axiz.config.environment=prod")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.lock.store=memory"));
  }

  @Test
  void dynamoDbLocksUseTheConfiguredTable() {
    runner
        .withPropertyValues(
            "axiz.lock.store=dynamodb",
            "axiz.lock.table=payments-locks",
            "axiz.aws.region=us-east-1",
            "axiz.config.environment=prod")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(DynamoDbClient.class);
              assertThat(ctx.getBean(DistributedLock.class))
                  .isInstanceOf(DynamoDbDistributedLock.class);
              assertThat(ctx).hasSingleBean(LockTemplate.class);
            });
  }

  @Test
  void redisWithoutAConnectionFactoryIsAConfigurationError() {
    runner
        .withPropertyValues("axiz.lock.store=redis", "axiz.config.environment=prod")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.lock.store=redis is not available"));
  }

  @Test
  void locksCanBeSwitchedOff() {
    runner
        .withPropertyValues("axiz.lock.enabled=false")
        .run(
            ctx ->
                assertThat(ctx)
                    .doesNotHaveBean(DistributedLock.class)
                    .doesNotHaveBean(LockTemplate.class));
  }
}
