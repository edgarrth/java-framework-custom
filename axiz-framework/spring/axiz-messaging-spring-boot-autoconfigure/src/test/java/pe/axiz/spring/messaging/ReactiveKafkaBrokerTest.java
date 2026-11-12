package pe.axiz.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import pe.axiz.adapter.messaging.kafka.KafkaEnvelopes;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(BlockHoundExtension.class)
class ReactiveKafkaBrokerTest {
  @Test
  @SuppressWarnings("unchecked")
  void coldStartPublishesBinaryCloudEventFromReactiveFlow() {
    ReactiveInfrastructureBootstrap.initialize();
    var broker = new EmbeddedKafkaKraftBroker(1, 1, "payments");
    broker.brokerProperty("offsets.topic.num.partitions", "1");
    broker.brokerProperty("group.initial.rebalance.delay.ms", "0");
    broker.brokerListProperty("axiz.test.kafka-brokers");
    broker.afterPropertiesSet();
    try {
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withConfiguration(
              AutoConfigurations.of(
                  AxizMessagingKafkaAutoConfiguration.class,
                  AxizMessagingAutoConfiguration.class,
                  AxizNativeMessagingAutoConfiguration.class))
          .withPropertyValues(
              "axiz.messaging.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
              "axiz.messaging.kafka.iam=false",
              "axiz.messaging.kafka.send-timeout=30s",
              "axiz.messaging.destinations.events=kafka:payments")
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                var event =
                    new EventEnvelope(
                        "event-1",
                        "urn:axiz:test",
                        "payment.v1",
                        Instant.now(),
                        "account-1",
                        "application/json",
                        "{}".getBytes(StandardCharsets.UTF_8),
                        Map.of());
                var template =
                    (KafkaTemplate<String, byte[]>)
                        ctx.getBean("axizAsyncKafkaTemplate", KafkaTemplate.class);
                var producerFactory =
                    (DefaultKafkaProducerFactory<?, ?>) template.getProducerFactory();
                producerFactory.updateConfigs(
                    Map.of("buffer.memory", 32768L, "batch.size", 32768, "max.block.ms", 2000L));
                var publisher = ctx.getBean(AsyncEventPublisher.class);
                StepVerifier.create(
                        Mono.fromCompletionStage(() -> publisher.publish("kafka:payments", event))
                            .subscribeOn(Schedulers.parallel()))
                    .assertNext(r -> assertEquals("event-1", r.eventId()))
                    .expectComplete()
                    .verify(Duration.ofSeconds(20));
                Map<String, Object> consumerProperties =
                    Map.of(
                        "bootstrap.servers",
                        broker.getBrokersAsString(),
                        "group.id",
                        UUID.randomUUID().toString(),
                        "auto.offset.reset",
                        "earliest",
                        "enable.auto.commit",
                        false);
                var partition = new TopicPartition("payments", 0);
                try (var consumer =
                    new KafkaConsumer<String, byte[]>(
                        consumerProperties,
                        new StringDeserializer(),
                        new ByteArrayDeserializer())) {
                  consumer.assign(List.of(partition));
                  var record =
                      KafkaTestUtils.getSingleRecord(consumer, "payments", Duration.ofSeconds(10));
                  assertEquals("account-1", record.key());
                  assertEquals("event-1", KafkaEnvelopes.from(record).id());
                  consumer.commitSync();
                }
                try (var restarted =
                    new KafkaConsumer<String, byte[]>(
                        consumerProperties,
                        new StringDeserializer(),
                        new ByteArrayDeserializer())) {
                  restarted.assign(List.of(partition));
                  assertEquals(1L, restarted.position(partition));
                  restarted.seekToBeginning(List.of(partition));
                  var replay =
                      KafkaTestUtils.getSingleRecord(restarted, "payments", Duration.ofSeconds(10));
                  assertEquals("event-1", KafkaEnvelopes.from(replay).id());
                }
                producerFactory.reset();
                producerFactory.updateConfigs(Map.of("linger.ms", 60000));
                var large =
                    new EventEnvelope(
                        "buffered",
                        "urn:test",
                        "paid.v1",
                        Instant.now(),
                        "account-1",
                        "application/octet-stream",
                        new byte[20000],
                        Map.of());
                var buffered =
                    Mono.fromCompletionStage(() -> publisher.publish("kafka:payments", large))
                        .subscribeOn(Schedulers.parallel())
                        .toFuture();
                Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(
                        () ->
                            template.metrics().entrySet().stream()
                                .anyMatch(
                                    entry ->
                                        entry.getKey().name().equals("buffer-available-bytes")
                                            && ((Number) entry.getValue().metricValue()).longValue()
                                                == 0));
                StepVerifier.create(
                        Mono.fromCompletionStage(() -> publisher.publish("kafka:payments", large))
                            .subscribeOn(Schedulers.parallel()))
                    .expectErrorMatches(ReactiveKafkaBrokerTest::sendTimeout)
                    .verify(Duration.ofSeconds(5));
                template.flush();
                assertEquals("buffered", buffered.get(5, TimeUnit.SECONDS).eventId());
                producerFactory.reset();
                producerFactory.updateConfigs(Map.of("linger.ms", 5));
                broker.getCluster().brokers().get(0).shutdown();
                broker.getCluster().brokers().get(0).startup();
                broker.getCluster().waitForReadyBrokers();
                StepVerifier.create(
                        Mono.fromCompletionStage(() -> publisher.publish("kafka:payments", event))
                            .subscribeOn(Schedulers.parallel()))
                    .assertNext(r -> assertEquals("event-1", r.eventId()))
                    .expectComplete()
                    .verify(Duration.ofSeconds(20));
              });
      assertTrue(BlockHoundExtension.detectedBlockingCalls().isEmpty());
    } finally {
      broker.destroy();
    }
  }

  @Test
  void unavailableBrokerFailsWithinDeadlineWithoutBlockingReactiveCaller() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingKafkaAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class,
                AxizNativeMessagingAutoConfiguration.class))
        .withPropertyValues(
            "axiz.messaging.kafka.bootstrap-servers=127.0.0.1:" + port,
            "axiz.messaging.kafka.iam=false",
            "axiz.messaging.kafka.send-timeout=200ms")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var event =
                  new EventEnvelope(
                      "unavailable",
                      "urn:test",
                      "paid.v1",
                      Instant.now(),
                      "account-1",
                      "application/json",
                      new byte[0],
                      Map.of());
              var publisher = ctx.getBean(AsyncEventPublisher.class);
              StepVerifier.create(
                      Mono.fromCompletionStage(() -> publisher.publish("kafka:unavailable", event))
                          .subscribeOn(Schedulers.parallel()))
                  .expectErrorMatches(ReactiveKafkaBrokerTest::sendTimeout)
                  .verify(Duration.ofSeconds(5));
            });
    assertTrue(BlockHoundExtension.detectedBlockingCalls().isEmpty());
  }

  private static boolean sendTimeout(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof java.util.concurrent.TimeoutException
          || current instanceof org.apache.kafka.common.errors.TimeoutException) return true;
    }
    return false;
  }
}
