package pe.axiz.spring.idempotency.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbAsyncIdempotencyStore;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.idempotency.AxizIdempotencyAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class IdempotencyDynamoDbTest {
  @Test
  void nativeStoreHasAnOwnedAsyncClientAndCannotFallBackToBlocking() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class,
                AxizIdempotencyDynamoDbAutoConfiguration.class))
        .withBean(
            AwsCredentialsProvider.class, () -> () -> AwsBasicCredentials.create("test", "fixture"))
        .withPropertyValues(
            "axiz.idempotency.store=dynamodb",
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=https://localhost:4566",
            "axiz.idempotency.dynamodb.table=dedup")
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(DynamoDbAsyncClient.class)
                  .hasSingleBean(AsyncAwsClients.class)
                  .doesNotHaveBean(DynamoDbClient.class)
                  .doesNotHaveBean(IdempotencyStore.class);
              assertThat(ctx.getBean(AsyncIdempotencyStore.class))
                  .isInstanceOf(DynamoDbAsyncIdempotencyStore.class);
            });
    for (String table : new String[] {null, "", " "}) {
      assertThatThrownBy(() -> new AxizIdempotencyDynamoDbProperties(table))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private final WebApplicationContextRunner servlet =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizAwsIdentityAutoConfiguration.class, AxizIdempotencyAutoConfiguration.class));

  @Test
  void theDynamoDbStoreIsUsedWhenChosen() {
    servlet
        .withConfiguration(AutoConfigurations.of(AxizIdempotencyDynamoDbAutoConfiguration.class))
        .withPropertyValues(
            "axiz.idempotency.store=dynamodb",
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=https://localhost:4566")
        .run(
            ctx -> {
              assertThat(ctx.getBean(IdempotencyStore.class))
                  .isInstanceOf(DynamoDbIdempotencyStore.class);
              assertThat(ctx).hasSingleBean(DynamoDbClient.class);
            });
  }
}
