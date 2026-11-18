package pe.axiz.spring.messaging.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.foundation.AxizFoundationAutoConfiguration;
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;
import pe.axiz.spring.messaging.AxizNativeMessagingAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQS applications must start and publish without any Kafka or SNS integration on their classpath.
 */
class SqsOnlyClasspathTest {
  private static final String DESTINATION =
      "https://sqs.us-east-1.amazonaws.com/000000000000/orders";

  private static FilteredClassLoader classpath() {
    return new FilteredClassLoader(
        "org.apache.kafka",
        "org.springframework.kafka",
        "org.springframework.boot.kafka",
        "pe.axiz.adapter.messaging.kafka",
        "software.amazon.awssdk.services.sns",
        "pe.axiz.adapter.messaging.sns");
  }

  private static EventEnvelope event() {
    return EventEnvelope.builder()
        .id("sqs-only")
        .source("urn:axiz:test")
        .type("payment.created.v1")
        .time(Instant.EPOCH)
        .subject(null)
        .dataContentType("application/json")
        .data(new byte[0])
        .extensions(Map.of())
        .build();
  }

  @Test
  void configuredDestinationsCreateOwnedBoundedClientsForEachExecutionModel() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class,
                AxizFoundationAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class,
                AxizNativeMessagingSqsAutoConfiguration.class,
                AxizNativeMessagingAutoConfiguration.class))
        .withBean(
            AwsCredentialsProvider.class, () -> () -> AwsBasicCredentials.create("test", "fixture"))
        .withBean(JsonMapper.class, JsonMapper::new)
        .withPropertyValues(
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=https://localhost:4566",
            "axiz.messaging.destinations.orders=" + DESTINATION)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SqsAsyncClient.class)
                    .hasSingleBean(AsyncAwsClients.class)
                    .doesNotHaveBean(SqsClient.class));
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class, AxizFoundationAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class, AxizMessagingSqsAutoConfiguration.class))
        .withBean(
            AwsCredentialsProvider.class, () -> () -> AwsBasicCredentials.create("test", "fixture"))
        .withBean(JsonMapper.class, JsonMapper::new)
        .withPropertyValues(
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=https://localhost:4566",
            "axiz.messaging.destinations.orders=" + DESTINATION)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SqsClient.class)
                    .doesNotHaveBean(SqsAsyncClient.class));
    assertThrows(
        IllegalArgumentException.class, () -> new AxizMessagingSqsClientProperties(Duration.ZERO));
  }

  @Test
  void imperativeSqsPublishingDoesNotNeedKafka() {
    var sqs = mock(SqsClient.class);
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenReturn(SendMessageResponse.builder().messageId("accepted").build());
    new ApplicationContextRunner()
        .withClassLoader(classpath())
        .withConfiguration(
            AutoConfigurations.of(
                AxizFoundationAutoConfiguration.class,
                AxizMessagingSqsAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class))
        .withBean(SqsClient.class, () -> sqs)
        .withBean(JsonMapper.class, JsonMapper::new)
        .withPropertyValues("axiz.messaging.destinations.orders=" + DESTINATION)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThrows(
                  ClassNotFoundException.class,
                  () ->
                      context
                          .getClassLoader()
                          .loadClass("org.springframework.kafka.core.KafkaTemplate"));
              assertEquals(
                  "accepted",
                  context
                      .getBean(EventPublisher.class)
                      .publish("orders", event())
                      .brokerMessageId());
            });
  }

  @Test
  void reactiveSqsPublishingDoesNotNeedKafka() {
    var sqs = mock(SqsAsyncClient.class);
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                SendMessageResponse.builder().messageId("accepted").build()));
    new ReactiveWebApplicationContextRunner()
        .withClassLoader(classpath())
        .withConfiguration(
            AutoConfigurations.of(
                AxizFoundationAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class,
                AxizNativeMessagingSqsAutoConfiguration.class,
                AxizNativeMessagingAutoConfiguration.class))
        .withBean(SqsAsyncClient.class, () -> sqs)
        .withBean(JsonMapper.class, JsonMapper::new)
        .withPropertyValues("axiz.messaging.destinations.orders=" + DESTINATION)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThrows(
                  ClassNotFoundException.class,
                  () ->
                      context
                          .getClassLoader()
                          .loadClass("org.apache.kafka.clients.producer.KafkaProducer"));
              assertEquals(
                  "accepted",
                  context
                      .getBean(AsyncEventPublisher.class)
                      .publish("orders", event())
                      .toCompletableFuture()
                      .get()
                      .brokerMessageId());
            });
  }
}
