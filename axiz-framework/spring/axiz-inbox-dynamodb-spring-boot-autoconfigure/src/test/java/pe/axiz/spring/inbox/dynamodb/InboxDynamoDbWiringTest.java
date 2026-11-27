package pe.axiz.spring.inbox.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class InboxDynamoDbWiringTest {
  @Test
  void theDynamoDbStoreUsesTheConfiguredTableAndEndpoint() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizInboxDynamoDbAutoConfiguration.class))
        .withPropertyValues(
            "axiz.inbox.store=dynamodb",
            "axiz.inbox.table=payments_inbox",
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=https://localhost:4566")
        .run(
            ctx -> {
              assertThat(ctx.getBean(InboxStore.class)).isInstanceOf(DynamoDbInboxStore.class);
              assertEquals(
                  URI.create("https://localhost:4566"),
                  ctx.getBean(DynamoDbClient.class)
                      .serviceClientConfiguration()
                      .endpointOverride()
                      .orElseThrow());
            });
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizInboxDynamoDbAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(InboxStore.class));
  }
}
