package pe.axiz.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.messaging.sns.AxizNativeMessagingSnsAutoConfiguration;
import pe.axiz.spring.messaging.sqs.AxizNativeMessagingSqsAutoConfiguration;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

class NativeMessagingProvidersTest {
  private static ReactiveWebApplicationContextRunner runner() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class,
                AxizNativeMessagingSqsAutoConfiguration.class,
                AxizNativeMessagingSnsAutoConfiguration.class,
                AxizNativeMessagingAutoConfiguration.class));
  }

  @Test
  void configuredAwsDestinationsOwnAndCloseTheirStartupResources() {
    runner()
        .withSystemProperties("aws.accessKeyId=local-test", "aws.secretAccessKey=local-test")
        .withPropertyValues(
            "axiz.aws.region=us-east-1",
            "axiz.messaging.aws.api-call-timeout=7s",
            "axiz.messaging.destinations.queue=http://localhost:4566/000000000000/queue",
            "axiz.messaging.destinations.topic=arn:aws:sns:us-east-1:000000000000:topic")
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(SqsAsyncClient.class)
                  .hasSingleBean(SnsAsyncClient.class)
                  .hasBean("axizMessagingSqsAsyncResources")
                  .hasBean("axizMessagingSnsAsyncResources");
              assertThat(
                      ctx.getBean(SqsAsyncClient.class)
                          .serviceClientConfiguration()
                          .overrideConfiguration()
                          .apiCallTimeout())
                  .contains(Duration.ofSeconds(7));
              assertThat(
                      ctx.getBean(SnsAsyncClient.class)
                          .serviceClientConfiguration()
                          .overrideConfiguration()
                          .apiCallTimeout())
                  .contains(Duration.ofSeconds(7));
            });
  }

  @Test
  void suppliedAwsClientsAreBorrowedAndNoOwnerIsCreated() {
    SqsAsyncClient sqs =
        new SqsAsyncClient() {
          public String serviceName() {
            return "sqs";
          }

          public void close() {}
        };
    SnsAsyncClient sns =
        new SnsAsyncClient() {
          public String serviceName() {
            return "sns";
          }

          public void close() {}
        };
    runner()
        .withBean(SqsAsyncClient.class, () -> sqs)
        .withBean(SnsAsyncClient.class, () -> sns)
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .doesNotHaveBean("axizMessagingSqsAsyncResources")
                  .doesNotHaveBean("axizMessagingSnsAsyncResources");
              assertThat(
                      ctx.getBeansOfType(AsyncEventPublisherAdapter.class).values().stream()
                          .map(AsyncEventPublisherAdapter::name))
                  .containsExactlyInAnyOrder("sqs", "sns");
            });
  }
}
