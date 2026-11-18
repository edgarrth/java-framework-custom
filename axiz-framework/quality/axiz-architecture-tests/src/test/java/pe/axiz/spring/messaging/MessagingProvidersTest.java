package pe.axiz.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.spring.messaging.sns.AxizMessagingSnsAutoConfiguration;
import pe.axiz.spring.messaging.sqs.AxizMessagingSqsAutoConfiguration;
import pe.axiz.spring.messaging.sqs.SqsEventConsumer;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class MessagingProvidersTest {
  /** An SQS client whose queues are always empty. */
  static final class EmptySqs implements SqsClient {
    final AtomicInteger polls = new AtomicInteger();

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
      polls.incrementAndGet();
      try {
        new CountDownLatch(1).await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return ReceiveMessageResponse.builder().messages(List.of()).build();
    }

    @Override
    public String serviceName() {
      return "sqs";
    }

    @Override
    public void close() {}
  }

  static final class NoSns implements SnsClient {
    @Override
    public String serviceName() {
      return "sns";
    }

    @Override
    public void close() {}
  }

  @Test
  void sqsAndSnsPublishersAndTheQueueConsumerUseTheProvidedClients() {
    EmptySqs sqs = new EmptySqs();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingAutoConfiguration.class,
                AxizMessagingSqsAutoConfiguration.class,
                AxizMessagingSnsAutoConfiguration.class))
        .withBean(SqsClient.class, () -> sqs)
        .withBean(SnsClient.class, NoSns::new)
        .withPropertyValues("axiz.messaging.sqs.queues=https://sqs.local/000000000000/payments")
        .run(
            ctx -> {
              assertThat(ctx.getBeansOfType(EventPublisherAdapter.class).values())
                  .extracting(EventPublisherAdapter::name)
                  .containsExactlyInAnyOrder("sqs", "sns");
              SqsEventConsumer consumer = ctx.getBean(SqsEventConsumer.class);
              assertTrue(consumer.isRunning(), "started with the context");
              Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> sqs.polls.get() > 0);
              assertTrue(sqs.polls.get() > 0, "the queue is polled");
              assertThat(ctx.getBean(EventPublisher.class)).isNotNull();
            });
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingAutoConfiguration.class,
                AxizMessagingSqsAutoConfiguration.class,
                AxizMessagingSnsAutoConfiguration.class))
        .withBean(SqsClient.class, EmptySqs::new)
        .withBean(SnsClient.class, NoSns::new)
        .run(ctx -> assertThat(ctx).doesNotHaveBean(SqsEventConsumer.class));
  }
}
