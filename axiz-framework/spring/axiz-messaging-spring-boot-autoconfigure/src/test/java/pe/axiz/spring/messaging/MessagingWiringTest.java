package pe.axiz.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.backoff.FixedBackOff;
import pe.axiz.core.messaging.CloudEventHeaders;
import pe.axiz.core.messaging.ConsumerRetryPolicy;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.PayloadException;
import pe.axiz.core.resilience.Backoff;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/** Defaults of the messaging wiring and how Kafka records and SQS queues reach the dispatcher. */
class MessagingWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizMessagingAutoConfiguration.class));

  public record Note(String text) {}

  /** A payload whose property cannot be read. */
  public static final class Unwritable {
    public String getValue() {
      throw new IllegalStateException("not readable");
    }
  }

  @Test
  void withoutJacksonOrInboxTheDefaultsStillWork() {
    runner
        .withPropertyValues("axiz.messaging.consumer.name=ledger")
        .run(
            ctx -> {
              PayloadCodec codec = ctx.getBean(PayloadCodec.class);
              assertInstanceOf(JacksonPayloadCodec.class, codec);
              assertEquals("application/json", codec.contentType());
              byte[] json = codec.encode(new Note("hola"));
              assertEquals(new Note("hola"), codec.decode(json, Note.class));
              assertThrows(PayloadException.class, () -> codec.encode(new Unwritable()));
              assertThrows(
                  PayloadException.class,
                  () -> codec.decode("{not json".getBytes(StandardCharsets.UTF_8), Note.class));

              EventEnvelope e =
                  ctx.getBean(EventFactory.class)
                      .create(null, "note.v1", null, new Note("x"), null);
              assertEquals("urn:axiz:application", e.source(), "no application name configured");
              assertThat(ctx).hasSingleBean(EventDispatcher.class);
            });
  }

  @Test
  void withoutAnInboxEveryDeliveryIsProcessed() {
    Deduplicator none = AxizMessagingAutoConfiguration.NoDeduplication.INSTANCE;
    Deduplicator.Claim claim = none.claim("c", "e-1");
    assertFalse(claim.token().isBlank());
    assertTrue(none.completed("c", "e-1", claim.token()));
    assertTrue(none.failed("c", "e-1", claim.token()));
  }

  @Test
  void eventsCanBeBuiltAndPublishedInTwoSteps() {
    runner
        .withBean(
            EventPublisherAdapter.class,
            () -> new EventPublisherAdapter("memory", new MessagingTest.Recorder()))
        .run(
            ctx -> {
              AxizEvents events = ctx.getBean(AxizEvents.class);
              EventEnvelope envelope = events.envelope("evt-1", "note.v1", "n-1", new Note("x"));
              assertEquals("evt-1", envelope.id());
              assertEquals("memory:notes", events.publish("memory:notes", envelope).destination());
            });
  }

  // --- Kafka ---------------------------------------------------------------------------------

  private static AxizMessagingProperties properties(
      AxizMessagingProperties.Kafka kafka, Duration deferInterval) {
    return new AxizMessagingProperties(
        null,
        "",
        Map.of(),
        new AxizMessagingProperties.Consumer(
            null,
            3,
            Duration.ofMillis(200),
            Duration.ofSeconds(5),
            false,
            List.of(),
            deferInterval,
            Duration.ofMinutes(1),
            true,
            Duration.ofSeconds(30)),
        kafka,
        new AxizMessagingProperties.Sqs(
            List.of(), Map.of(), Duration.ofSeconds(20), 10, Duration.ofSeconds(60)),
        new AxizMessagingProperties.Routes("domain-events", Map.of()));
  }

  private static AxizMessagingProperties.Kafka kafka(String servers, boolean iam) {
    return new AxizMessagingProperties.Kafka(
        servers, iam, List.of("payments", "refunds"), null, 2, ".dlq", Duration.ofSeconds(1));
  }

  @Test
  void kafkaClientsUseTheConfiguredServersAndIamUnlessTurnedOff() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.application.name", "payments")
            .withProperty("spring.kafka.bootstrap-servers", "broker:9092");
    Map<String, Object> iam =
        AxizMessagingKafkaAutoConfiguration.common(
            properties(kafka(null, true), Duration.ofSeconds(10)), env);
    assertEquals("broker:9092", iam.get("bootstrap.servers"), "Spring's setting is the fallback");
    assertEquals("payments", iam.get("client.id"));
    assertEquals("AWS_MSK_IAM", iam.get("sasl.mechanism"));

    Map<String, Object> plain =
        AxizMessagingKafkaAutoConfiguration.common(
            properties(kafka("local:9092", false), Duration.ofSeconds(10)), env);
    assertEquals("local:9092", plain.get("bootstrap.servers"));
    assertFalse(plain.containsKey("sasl.mechanism"));
  }

  @Test
  void theProducerIsReliableAndBackedByAnAdapter() {
    AxizMessagingProperties p = properties(kafka("localhost:9092", false), Duration.ofSeconds(10));
    AxizMessagingKafkaAutoConfiguration config = new AxizMessagingKafkaAutoConfiguration();
    Map<String, Object> producerProperties =
        config.axizKafkaProducerProperties(p, new MockEnvironment());
    KafkaProducer<String, byte[]> producer = config.axizKafkaProducer(producerProperties);
    try {
      EventPublisherAdapter adapter =
          config.axizKafkaPublisherAdapter(producer, p, producerProperties);
      assertEquals("kafka", adapter.name());
      assertTrue(adapter.publisher().supports("payments"));
    } finally {
      producer.close(Duration.ZERO);
    }
  }

  @Test
  void oneListenerContainerPerTopicIsPreparedWithoutStarting() {
    AxizMessagingProperties p = properties(kafka("localhost:9092", false), Duration.ofSeconds(10));
    KafkaConsumers consumers =
        new AxizMessagingKafkaAutoConfiguration.ConsumerConfiguration()
            .axizKafkaConsumers(
                p,
                new MockEnvironment().withProperty("spring.application.name", "payments"),
                dispatcherAnswering(DeliveryDecision.ACK, new AtomicInteger()));
    assertFalse(consumers.isRunning());
    assertEquals(Integer.MAX_VALUE - 100, consumers.getPhase());
    consumers.stop();
    assertFalse(consumers.isRunning());
  }

  private static ObservedDispatcher dispatcherAnswering(
      DeliveryDecision decision, AtomicInteger lastAttempt) {
    EventDispatcher real =
        new EventDispatcher(
            "c",
            List.of(),
            SqsEventConsumerTest.CODEC,
            Deduplicator.none(),
            ConsumerRetryPolicy.defaults(),
            true);
    return new ObservedDispatcher(real, ObservationRegistry.NOOP, Clock.systemUTC()) {
      @Override
      public EventDispatcher.Outcome dispatch(String source, EventEnvelope envelope, int attempt) {
        lastAttempt.set(attempt);
        return new EventDispatcher.Outcome(decision, Duration.ofSeconds(30));
      }
    };
  }

  private static ConsumerRecord<String, byte[]> record(Integer deliveryAttempt) {
    EventEnvelope envelope =
        new EventEnvelope(
            "e-1",
            "urn:test",
            "pe.axiz.test.thing.v1",
            Instant.EPOCH,
            null,
            "text/plain",
            "hello".getBytes(StandardCharsets.UTF_8),
            Map.of());
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>("payments", 0, 42L, "e-1", envelope.data());
    CloudEventHeaders.toHeaders(envelope)
        .forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
    if (deliveryAttempt != null) {
      record
          .headers()
          .add("kafka_deliveryAttempt", ByteBuffer.allocate(4).putInt(deliveryAttempt).array());
    }
    return record;
  }

  @Test
  void eachDecisionTellsTheContainerWhatToDoWithTheRecord() {
    AtomicInteger attempt = new AtomicInteger();
    AxizMessagingKafkaAutoConfiguration.ConsumerConfiguration.handle(
        dispatcherAnswering(DeliveryDecision.ACK, attempt), record(null));
    assertEquals(1, attempt.get(), "the first delivery has no attempt header");

    RuntimeException retry =
        assertThrows(
            RuntimeException.class,
            () ->
                AxizMessagingKafkaAutoConfiguration.ConsumerConfiguration.handle(
                    dispatcherAnswering(DeliveryDecision.RETRY, attempt), record(3)));
    assertEquals(3, attempt.get());
    assertInstanceOf(AxizMessagingKafkaAutoConfiguration.RetryLaterException.class, retry);
    assertThat(retry.getMessage()).contains("payments@42");

    RuntimeException defer =
        assertThrows(
            RuntimeException.class,
            () ->
                AxizMessagingKafkaAutoConfiguration.ConsumerConfiguration.handle(
                    dispatcherAnswering(DeliveryDecision.DEFER, attempt), record(2)));
    assertTrue(AxizMessagingKafkaAutoConfiguration.deferred(new IllegalStateException(defer)));

    RuntimeException dead =
        assertThrows(
            RuntimeException.class,
            () ->
                AxizMessagingKafkaAutoConfiguration.ConsumerConfiguration.handle(
                    dispatcherAnswering(DeliveryDecision.DEAD_LETTER, attempt), record(5)));
    assertInstanceOf(AxizMessagingKafkaAutoConfiguration.DeadLetterException.class, dead);
    assertFalse(AxizMessagingKafkaAutoConfiguration.deferred(dead));
    assertFalse(AxizMessagingKafkaAutoConfiguration.deferred(null));
  }

  @Test
  void deferredRecordsWaitForTheLeaseWithAFloorOfOneSecond() {
    FixedBackOff normal =
        AxizMessagingKafkaAutoConfiguration.deferBackOff(
            properties(kafka("k", false), Duration.ofSeconds(10)));
    assertEquals(10_000, normal.getInterval());
    assertEquals(6, normal.getMaxAttempts(), "one minute of waiting in ten-second steps");

    FixedBackOff tiny =
        AxizMessagingKafkaAutoConfiguration.deferBackOff(
            properties(kafka("k", false), Duration.ofMillis(10)));
    assertEquals(1_000, tiny.getInterval());
    assertEquals(60, tiny.getMaxAttempts());
  }

  @Test
  void kafkaIsOnlyWiredWhenAServerIsConfigured() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingAutoConfiguration.class, AxizMessagingKafkaAutoConfiguration.class))
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(KafkaProducer.class);
              assertThat(ctx).doesNotHaveBean(KafkaConsumers.class);
            });
  }

  // --- AWS -----------------------------------------------------------------------------------

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
                AxizMessagingAutoConfiguration.class, AxizMessagingAwsAutoConfiguration.class))
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
              org.awaitility.Awaitility.await()
                  .atMost(Duration.ofSeconds(5))
                  .until(() -> sqs.polls.get() > 0);
              assertTrue(sqs.polls.get() > 0, "the queue is polled");
              assertEquals(0, consumer.inFlightCount());
              assertThat(ctx.getBean(EventPublisher.class)).isNotNull();
            });
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingAutoConfiguration.class, AxizMessagingAwsAutoConfiguration.class))
        .withBean(SqsClient.class, EmptySqs::new)
        .withBean(SnsClient.class, NoSns::new)
        .run(ctx -> assertThat(ctx).doesNotHaveBean(SqsEventConsumer.class));
  }

  /** Answers one message, then fails once, then has nothing more. */
  static final class ScriptedSqs implements SqsClient {
    final AtomicInteger receives = new AtomicInteger();
    final List<String> settled = new CopyOnWriteArrayList<>();

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
      int n = receives.incrementAndGet();
      if (n == 1) {
        return ReceiveMessageResponse.builder()
            .messages(
                Message.builder()
                    .messageId("m-1")
                    .receiptHandle("r-1")
                    .body(SqsEventConsumerTest.validBody())
                    .build())
            .build();
      }
      if (n == 2) {
        throw new IllegalStateException("throttled");
      }
      try {
        new CountDownLatch(1).await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return ReceiveMessageResponse.builder().messages(List.of()).build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(Consumer<DeleteMessageRequest.Builder> request) {
      settled.add("delete");
      return DeleteMessageResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "sqs";
    }

    @Override
    public void close() {}
  }

  @Test
  void theConsumerProcessesWhatArrivesAndKeepsPollingAfterAFailedReceive() throws Exception {
    ScriptedSqs sqs = new ScriptedSqs();
    SqsEventConsumerTest.Handler handler = new SqsEventConsumerTest.Handler();
    EventDispatcher dispatcher =
        new EventDispatcher(
            "c",
            List.of(handler),
            SqsEventConsumerTest.CODEC,
            Deduplicator.none(),
            ConsumerRetryPolicy.defaults(),
            false);
    SqsEventConsumer consumer =
        new SqsEventConsumer(
            sqs,
            new ObservedDispatcher(dispatcher, ObservationRegistry.NOOP, Clock.systemUTC()),
            new AxizMessagingProperties.Sqs(
                List.of("https://sqs.local/000000000000/payments"),
                Map.of(),
                Duration.ofSeconds(20),
                10,
                Duration.ofSeconds(4)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1)));
    consumer.start();
    try {
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .until(() -> sqs.receives.get() >= 3 && consumer.inFlightCount() == 0);
      assertTrue(sqs.receives.get() >= 3, "polling resumed after the failed receive");
      assertEquals(List.of("hello"), handler.seen);
      assertEquals(List.of("delete"), sqs.settled);
      assertEquals(0, consumer.inFlightCount());
      consumer.renewVisibility(); // nothing in flight: no call
    } finally {
      consumer.stop();
    }
    assertFalse(consumer.isRunning());
    assertEquals(Integer.MAX_VALUE - 100, consumer.getPhase());
  }

  @Test
  void messagingCanBeSwitchedOff() {
    runner
        .withPropertyValues("axiz.messaging.enabled=false")
        .run(ctx -> assertNull(ctx.getBeanProvider(AxizEvents.class).getIfAvailable()));
  }
}
