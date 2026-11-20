package pe.axiz.spring.messaging.sns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.messaging.AsyncEventPublisherAdapter;
import pe.axiz.spring.messaging.EventPublisherAdapter;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.SnsClient;

class SnsProviderContractTest {
  @Test
  void ownsConfiguredClientsAndOnlyCreatesANativeTransportWhenSnsIsNeeded() {
    var identity = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    String[] settings = {
      "axiz.aws.region=us-east-1",
      "axiz.aws.endpoint=http://127.0.0.1:9",
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true"
    };
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class, AxizMessagingSnsAutoConfiguration.class))
        .withBean(AwsCredentialsProvider.class, () -> identity)
        .withPropertyValues(settings)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SnsClient.class)
                    .hasSingleBean(EventPublisherAdapter.class));
    var nativeRunner =
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAwsIdentityAutoConfiguration.class,
                    AxizNativeMessagingSnsAutoConfiguration.class))
            .withBean(AwsCredentialsProvider.class, () -> identity)
            .withPropertyValues(settings);
    nativeRunner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(SnsAsyncClient.class)
                .doesNotHaveBean(AsyncEventPublisherAdapter.class));
    nativeRunner
        .withPropertyValues("axiz.messaging.destinations.orders=orders")
        .run(context -> assertThat(context).doesNotHaveBean(SnsAsyncClient.class));
    nativeRunner
        .withPropertyValues(
            "axiz.messaging.destinations.orders=arn:aws:sqs:us-east-1:000000000000:orders")
        .run(context -> assertThat(context).doesNotHaveBean(SnsAsyncClient.class));
    nativeRunner
        .withPropertyValues(
            "axiz.messaging.destinations.orders=arn:aws:sns:us-east-1:000000000000:orders")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SnsAsyncClient.class)
                    .hasSingleBean(AsyncEventPublisherAdapter.class)
                    .doesNotHaveBean(SnsClient.class));
    nativeRunner
        .withBean(SnsAsyncClient.class, () -> mock(SnsAsyncClient.class))
        .run(
            context ->
                assertThat(context).hasNotFailed().hasSingleBean(AsyncEventPublisherAdapter.class));
  }

  @Test
  void explicitlySuppliedClientIsUsedAndDisableSuppressesTheAdapter() {
    var client = mock(SnsClient.class);
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizMessagingSnsAutoConfiguration.class))
            .withBean(SnsClient.class, () -> client);
    runner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(EventPublisherAdapter.class)
                .hasSingleBean(SnsClient.class));
    runner
        .withPropertyValues("axiz.messaging.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(EventPublisherAdapter.class));
  }

  @Test
  void networkCallsAlwaysHaveAPositiveDeadline() {
    assertThrows(
        IllegalArgumentException.class, () -> new AxizMessagingSnsClientProperties(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizMessagingSnsClientProperties(Duration.ofNanos(-1)));
  }
}
