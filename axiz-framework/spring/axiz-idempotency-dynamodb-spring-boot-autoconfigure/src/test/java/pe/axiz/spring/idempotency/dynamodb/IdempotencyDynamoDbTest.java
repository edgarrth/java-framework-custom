package pe.axiz.spring.idempotency.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.idempotency.AxizIdempotencyAutoConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class IdempotencyDynamoDbTest {
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
