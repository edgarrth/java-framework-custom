package pe.axiz.spring.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Sequence counters on DynamoDB for services without a relational database. */
class SequenceDynamoDbTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSequenceDynamoDbAutoConfiguration.class));

  @Test
  void theCountersLiveInDynamoDbWithTheConfiguredEndpoint() {
    runner
        .withPropertyValues(
            "axiz.sequence.store=dynamodb",
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=http://localhost:4566")
        .run(
            ctx -> {
              assertThat(ctx.getBean(SequenceStore.class))
                  .isInstanceOf(DynamoDbSequenceStore.class);
              assertEquals(
                  URI.create("http://localhost:4566"),
                  ctx.getBean(DynamoDbClient.class)
                      .serviceClientConfiguration()
                      .endpointOverride()
                      .orElseThrow());
            });
  }

  @Test
  void otherStoresDoNotCreateIt() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SequenceStore.class));
  }
}
