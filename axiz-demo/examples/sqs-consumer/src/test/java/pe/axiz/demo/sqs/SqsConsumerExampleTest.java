package pe.axiz.demo.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.spring.messaging.AxizEvents;
import pe.axiz.spring.messaging.ReactiveEvents;
import pe.axiz.spring.messaging.ReactiveTypedEventHandler;
import pe.axiz.spring.messaging.sqs.AxizMessagingSqsProperties;
import pe.axiz.spring.messaging.sqs.SqsEventConsumer;
import pe.axiz.spring.test.AxizContainers;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** Minimal consumer classpath: no hand-picked AutoConfigurations and no broker mocks. */
class SqsConsumerExampleTest {
  private static final String TYPE = "demo.receipt.created";
  private static final Duration WAIT = Duration.ofSeconds(30);

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class ConsumerApplication {}

  public record Receipt(String id) {}

  static class Received {
    final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    final List<String> handled = new CopyOnWriteArrayList<>();

    void accept(Receipt receipt) {
      int attempt =
          attempts.computeIfAbsent(receipt.id(), ignored -> new AtomicInteger()).incrementAndGet();
      if (receipt.id().startsWith("poison"))
        throw new IllegalArgumentException("synthetic permanent rejection");
      if ((receipt.id().equals("retry") || receipt.id().equals("fifo-1")) && attempt == 1)
        throw new IllegalStateException("synthetic transient failure");
      handled.add(receipt.id());
    }
  }

  @HandlesEvent(TYPE)
  static class NativeHandler extends ReactiveTypedEventHandler<Receipt> {
    private final Received received;

    NativeHandler(Received received) {
      this.received = received;
    }

    @Override
    protected Publisher<Void> on(Receipt receipt, EventEnvelope event) {
      return Mono.fromRunnable(() -> received.accept(receipt));
    }
  }

  static class BlockingHandler implements EventHandler<Receipt> {
    private final Received received;

    BlockingHandler(Received received) {
      this.received = received;
    }

    public String eventType() {
      return TYPE;
    }

    public Class<Receipt> payloadType() {
      return Receipt.class;
    }

    public void handle(Receipt receipt, EventEnvelope event) {
      received.accept(receipt);
    }
  }

  @Test
  void discoveredStarterConsumesRetriesDeadLettersAndPreservesFifoOnBothStacks(
      TestReporter evidence) {
    try (var aws = AxizContainers.localstack()) {
      aws.start();
      var identity =
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(aws.getAccessKey(), aws.getSecretKey()));
      try (var sqs =
          SqsClient.builder()
              .credentialsProvider(identity)
              .region(Region.of(aws.getRegion()))
              .endpointOverride(aws.getEndpoint())
              .httpClientBuilder(UrlConnectionHttpClient.builder())
              .build()) {
        for (String stack : List.of("servlet", "reactive")) {
          String directDlq = sqs.createQueue(b -> b.queueName(stack + "-direct-dlq")).queueUrl();
          String redriveDlq = sqs.createQueue(b -> b.queueName(stack + "-redrive-dlq")).queueUrl();
          String arn =
              sqs.getQueueAttributes(
                      b -> b.queueUrl(redriveDlq).attributeNames(QueueAttributeName.QUEUE_ARN))
                  .attributes()
                  .get(QueueAttributeName.QUEUE_ARN);
          String standard = sqs.createQueue(b -> b.queueName(stack + "-standard")).queueUrl();
          String redrive =
              sqs.createQueue(
                      b ->
                          b.queueName(stack + "-redrive")
                              .attributes(
                                  Map.of(
                                      QueueAttributeName.REDRIVE_POLICY,
                                      "{\"deadLetterTargetArn\":\""
                                          + arn
                                          + "\",\"maxReceiveCount\":\"2\"}")))
                  .queueUrl();
          String fifo =
              sqs.createQueue(
                      b ->
                          b.queueName(stack + "-receipts.fifo")
                              .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
                  .queueUrl();
          String fifoDlq =
              sqs.createQueue(
                      b ->
                          b.queueName(stack + "-dlq.fifo")
                              .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
                  .queueUrl();
          var received = new Received();
          String[] properties = {
            "spring.application.name=sqs-consumer-example",
            "axiz.config.environment=test",
            "axiz.transport.allow-plain-http=true",
            "axiz.aws.endpoint=" + aws.getEndpoint(),
            "axiz.aws.region=" + aws.getRegion(),
            "axiz.messaging.sqs.queues[0]=" + standard,
            "axiz.messaging.sqs.queues[1]=" + redrive,
            "axiz.messaging.sqs.queues[2]=" + fifo,
            "axiz.messaging.sqs.wait-time=1s",
            "axiz.messaging.sqs.visibility-timeout=3s",
            "axiz.messaging.consumer.initial-delay=1s",
            "axiz.messaging.consumer.max-delay=1s",
            "axiz.messaging.consumer.max-attempts=2",
            "axiz.messaging.consumer.subscriptions[0]=" + TYPE,
            "axiz.messaging.destinations.standard=" + standard,
            "axiz.messaging.destinations.redrive=" + redrive,
            "axiz.messaging.destinations.fifo=" + fifo
          };
          if (stack.equals("servlet")) {
            new WebApplicationContextRunner()
                .withUserConfiguration(ConsumerApplication.class)
                .withPropertyValues(properties)
                .withInitializer(
                    context ->
                        context
                            .getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                new MapPropertySource(
                                    "demo-dlq",
                                    Map.of(
                                        "axiz.messaging.sqs.dead-letter-queues[" + standard + "]",
                                        directDlq,
                                        "axiz.messaging.sqs.dead-letter-queues[" + fifo + "]",
                                        fifoDlq))))
                .withBean(AwsCredentialsProvider.class, () -> identity)
                .withBean(BlockingHandler.class, () -> new BlockingHandler(received))
                .run(
                    context ->
                        demonstrate(
                            context,
                            sqs,
                            directDlq,
                            redriveDlq,
                            fifoDlq,
                            received,
                            stack,
                            evidence));
          } else {
            new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(ConsumerApplication.class)
                .withPropertyValues(properties)
                .withInitializer(
                    context ->
                        context
                            .getEnvironment()
                            .getPropertySources()
                            .addFirst(
                                new MapPropertySource(
                                    "demo-dlq",
                                    Map.of(
                                        "axiz.messaging.sqs.dead-letter-queues[" + standard + "]",
                                        directDlq,
                                        "axiz.messaging.sqs.dead-letter-queues[" + fifo + "]",
                                        fifoDlq))))
                .withBean(AwsCredentialsProvider.class, () -> identity)
                .withBean(NativeHandler.class, () -> new NativeHandler(received))
                .run(
                    context ->
                        demonstrate(
                            context,
                            sqs,
                            directDlq,
                            redriveDlq,
                            fifoDlq,
                            received,
                            stack,
                            evidence));
          }
        }
      }
    }
  }

  private static void demonstrate(
      ApplicationContext context,
      SqsClient sqs,
      String directDlq,
      String redriveDlq,
      String fifoDlq,
      Received received,
      String stack,
      TestReporter evidence) {
    assertThat(context.getBean(AxizMessagingSqsProperties.class).deadLetterQueues())
        .containsValue(directDlq);
    assertThat(context.getBean(SqsEventConsumer.class).isRunning()).isTrue();
    context.getBean(SqsEventConsumer.class).stop();
    send(context, stack, "standard", "ack");
    send(context, stack, "standard", "retry");
    send(context, stack, "standard", "poison-direct");
    send(context, stack, "redrive", "poison-redrive");
    send(context, stack, "fifo", "fifo-1");
    send(context, stack, "fifo", "fifo-1"); // same event id is deduplicated by FIFO
    send(context, stack, "fifo", "fifo-2");
    send(context, stack, "fifo", "poison-fifo");
    context.getBean(SqsEventConsumer.class).start();
    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> {
              assertThat(received.handled).contains("ack", "retry", "fifo-1", "fifo-2");
              assertThat(received.attempts.get("retry").get()).isEqualTo(2);
              assertThat(received.attempts.get("fifo-1").get()).isEqualTo(2);
            });
    assertDeadLetter(sqs, directDlq, "poison-direct");
    assertDeadLetter(sqs, redriveDlq, "poison-redrive");
    assertDeadLetter(sqs, fifoDlq, "poison-fifo");
    context.getBean(SqsEventConsumer.class).stop();
    assertThat(received.handled.stream().filter(value -> value.startsWith("fifo")).toList())
        .containsExactly("fifo-1", "fifo-2");
    assertThat(received.attempts.get("poison-direct").get()).isEqualTo(1);
    assertThat(received.attempts.get("poison-redrive").get()).isEqualTo(2);
    evidence.publishEntry("axiz.capability.messaging-sqs", stack);
    for (String operation :
        List.of("consumer", "retry", "dlq", "redrive", "fifo", "fifo-dlq", "discovery"))
      evidence.publishEntry("axiz.capability.messaging-sqs", operation);
  }

  private static void send(
      ApplicationContext context, String stack, String destination, String id) {
    var event =
        EventEnvelope.builder()
            .id(id)
            .source("urn:demo:sqs")
            .type(TYPE)
            .time(Instant.EPOCH)
            .subject("customer-1")
            .dataContentType("application/json")
            .data(("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8))
            .build();
    if (stack.equals("reactive"))
      context.getBean(ReactiveEvents.class).publish(destination, event).block(WAIT);
    else context.getBean(AxizEvents.class).publish(destination, event);
  }

  private static void assertDeadLetter(SqsClient sqs, String queue, String id) {
    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> {
              var messages =
                  sqs.receiveMessage(
                          b ->
                              b.queueUrl(queue)
                                  .waitTimeSeconds(1)
                                  .messageSystemAttributeNames(
                                      MessageSystemAttributeName.MESSAGE_GROUP_ID))
                      .messages();
              assertThat(messages).hasSize(1);
              assertThat(CloudEventJson.read(messages.getFirst().body()).id()).isEqualTo(id);
              if (queue.endsWith(".fifo"))
                assertThat(
                        messages
                            .getFirst()
                            .attributes()
                            .get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
                    .isEqualTo("customer-1");
              sqs.deleteMessage(
                  b -> b.queueUrl(queue).receiptHandle(messages.getFirst().receiptHandle()));
            });
  }
}
