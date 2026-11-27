package pe.axiz.spring.lock.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class DynamoLockProviderTest {
  @Test
  void ownsClientsForBothStacksWithAnExplicitIdentityAndNeverFallsBackToAwsDefaults() {
    var configurations =
        AutoConfigurations.of(
            AxizAwsIdentityAutoConfiguration.class, AxizLockDynamoDbAutoConfiguration.class);
    var identity = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    String[] settings = {
      "axiz.lock.store=dynamodb",
      "axiz.aws.region=us-east-1",
      "axiz.aws.endpoint=http://127.0.0.1:9",
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true"
    };
    new ApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, () -> identity)
        .withPropertyValues(settings)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(DistributedLock.class)
                    .hasSingleBean(DynamoDbClient.class)
                    .doesNotHaveBean(DynamoDbAsyncClient.class));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, () -> identity)
        .withPropertyValues(settings)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AsyncDistributedLock.class)
                    .hasSingleBean(DynamoDbAsyncClient.class)
                    .doesNotHaveBean(DynamoDbClient.class));
  }

  @Test
  void suppliedSdkClientsBackTheSelectedExecutionStack() {
    var configurations = AutoConfigurations.of(AxizLockDynamoDbAutoConfiguration.class);
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(configurations)
            .withBean(DynamoDbClient.class, () -> mock(DynamoDbClient.class))
            .withPropertyValues("axiz.lock.store=dynamodb");
    runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(DistributedLock.class));
    runner
        .withBean(Clock.class, Clock::systemUTC)
        .run(context -> assertThat(context).hasNotFailed());
    runner
        .withPropertyValues("axiz.lock.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DistributedLock.class));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(DynamoDbAsyncClient.class, () -> mock(DynamoDbAsyncClient.class))
        .withPropertyValues("axiz.lock.store=dynamodb")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(AsyncDistributedLock.class)
                    .doesNotHaveBean(DistributedLock.class));
  }

  @Test
  void missingTableCannotSilentlySelectAnotherResource() {
    assertThrows(IllegalArgumentException.class, () -> new AxizLockDynamoDbProperties(null));
    assertThrows(IllegalArgumentException.class, () -> new AxizLockDynamoDbProperties(" "));
  }
}
