package pe.axiz.spring.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Scheduled-task locks on DynamoDB for services without a relational database. */
class SchedulingDynamoDbTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSchedulingDynamoDbAutoConfiguration.class));

  @Test
  void theLocksLiveInDynamoDbWithTheConfiguredEndpoint() {
    runner
        .withPropertyValues(
            "axiz.scheduling.store=dynamodb",
            "axiz.aws.region=us-east-1",
            "axiz.aws.dynamodb.endpoint=http://localhost:8000")
        .run(
            ctx -> {
              assertThat(ctx.getBean(LockProvider.class)).isInstanceOf(DynamoDBLockProvider.class);
              assertEquals(
                  URI.create("http://localhost:8000"),
                  ctx.getBean(DynamoDbClient.class)
                      .serviceClientConfiguration()
                      .endpointOverride()
                      .orElseThrow());
            });
  }

  @Test
  void otherStoresDoNotCreateIt() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LockProvider.class));
  }
}
