package pe.axiz.spring.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;
import pe.axiz.spring.messaging.AxizMessagingProperties;
import pe.axiz.spring.messaging.EventPublisherAdapter;
import pe.axiz.spring.messaging.ObservedDispatcher;

class KafkaMessagingWiringTest {
  // --- Kafka ---------------------------------------------------------------------------------

  private static AxizMessagingProperties properties(Duration deferInterval) {
    return new AxizMessagingProperties(
        null,
        "",
        Map.of(),
        AxizMessagingProperties.Consumer.builder()
            .name(null)
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(200))
            .maxDelay(Duration.ofSeconds(5))
            .ignoreUnknownTypes(false)
            .subscriptions(List.of())
            .deferInterval(deferInterval)
            .deferMaxWait(Duration.ofMinutes(1))
            .transactional(true)
            .handlerTimeout(Duration.ofSeconds(30))
            .build(),
        new AxizMessagingProperties.Routes("domain-events", Map.of()));
  }

  private static MockEnvironment localEnvironment() {
    return new MockEnvironment()
        .withProperty("axiz.config.environment", "test")
        .withProperty("axiz.transport.allow-insecure-data-stores", "true");
  }

  private static AxizMessagingKafkaProperties kafka(String servers, boolean iam) {
    return new AxizMessagingKafkaProperties(
        servers,
        iam ? "fixture" : "configured",
        List.of("payments", "refunds"),
        null,
        2,
        ".dlq",
        Duration.ofSeconds(1));
  }

  @Test
  void kafkaClientsUseTheConfiguredServersAndIamUnlessTurnedOff() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.application.name", "payments")
            .withProperty("spring.kafka.bootstrap-servers", "broker:9092");
    Map<String, Object> iam =
        AxizMessagingKafkaAutoConfiguration.common(
            kafka(null, true),
            env,
            authentication(),
            null,
            pe.axiz.spring.kafka.KafkaConnectionProperties.ClientKind.PRODUCER);
    assertEquals("broker:9092", iam.get("bootstrap.servers"), "Spring's setting is the fallback");
    assertEquals("payments", iam.get("client.id"));
    assertEquals("AWS_MSK_IAM", iam.get("sasl.mechanism"));

    Map<String, Object> plain =
        AxizMessagingKafkaAutoConfiguration.common(
            kafka("local:9092", false),
            env,
            authentication(),
            null,
            pe.axiz.spring.kafka.KafkaConnectionProperties.ClientKind.PRODUCER);
    assertEquals("local:9092", plain.get("bootstrap.servers"));
    assertFalse(plain.containsKey("sasl.mechanism"));
  }

  @Test
  void theProducerIsReliableAndBackedByAnAdapter() {
    AxizMessagingKafkaProperties p = kafka("localhost:9092", false);
    AxizMessagingKafkaAutoConfiguration config = new AxizMessagingKafkaAutoConfiguration();
    Map<String, Object> producerProperties =
        config.axizKafkaProducerProperties(p, new MockEnvironment(), authentication(), null);
    KafkaProducer<String, byte[]> producer =
        config.axizKafkaProducer(producerProperties, localEnvironment());
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
    AxizMessagingKafkaProperties p = kafka("localhost:9092", false);
    KafkaConsumers consumers =
        new AxizMessagingKafkaAutoConfiguration.ConsumerConfiguration()
            .axizKafkaConsumers(
                p,
                properties(Duration.ofSeconds(10)),
                localEnvironment().withProperty("spring.application.name", "payments"),
                dispatcherAnswering(DeliveryDecision.ACK, new AtomicInteger()),
                authentication(),
                null);
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
            new pe.axiz.spring.messaging.JacksonPayloadCodec(
                tools.jackson.databind.json.JsonMapper.builder().build()),
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
        EventEnvelope.builder()
            .id("e-1")
            .source("urn:test")
            .type("pe.axiz.test.thing.v1")
            .time(Instant.EPOCH)
            .subject(null)
            .dataContentType("text/plain")
            .data("hello".getBytes(StandardCharsets.UTF_8))
            .extensions(Map.of())
            .build();
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
        AxizMessagingKafkaAutoConfiguration.deferBackOff(properties(Duration.ofSeconds(10)));
    assertEquals(10_000, normal.getInterval());
    assertEquals(6, normal.getMaxAttempts(), "one minute of waiting in ten-second steps");

    FixedBackOff tiny =
        AxizMessagingKafkaAutoConfiguration.deferBackOff(properties(Duration.ofMillis(10)));
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

  private static org.springframework.beans.factory.ObjectProvider<KafkaClientAuthentication>
      authentication() {
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton(
        "fixture",
        new KafkaClientAuthentication() {
          @Override
          public String name() {
            return "fixture";
          }

          @Override
          public void configure(Map<String, Object> values) {
            values.put("sasl.mechanism", "AWS_MSK_IAM");
          }
        });
    return beans.getBeanProvider(KafkaClientAuthentication.class);
  }
}
