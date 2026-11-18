package pe.axiz.spring.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import pe.axiz.adapter.messaging.kafka.KafkaEventRecords;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.spring.foundation.BoundedAsyncDispatcher;
import pe.axiz.spring.messaging.AsyncEventPublisherAdapter;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@ExtendWith(BlockHoundExtension.class)
class ReactiveKafkaTest {
  @Test
  @SuppressWarnings("unchecked")
  void isolatesSendAdmissionAndCompletesOnlyOnBrokerAck() throws Exception {
    var template = (KafkaTemplate<String, byte[]>) mock(KafkaTemplate.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ack = new CompletableFuture<SendResult<String, byte[]>>();
    var event =
        EventEnvelope.builder()
            .id("e-1")
            .source("urn:test")
            .type("paid.v1")
            .time(Instant.EPOCH)
            .subject("account-1")
            .dataContentType("application/json")
            .data(new byte[0])
            .extensions(Map.of())
            .build();
    var record = KafkaEventRecords.create("kafka:payments", event);
    when(template.send(any(ProducerRecord.class)))
        .thenAnswer(
            invocation -> {
              assertTrue(Thread.currentThread().getName().startsWith("kafka-test-send-"));
              entered.countDown();
              assertTrue(release.await(3, TimeUnit.SECONDS));
              return ack;
            });
    try (var sends =
        new BoundedAsyncDispatcher(
            "kafka-test", 1, 1, Duration.ofSeconds(5), new SimpleMeterRegistry())) {
      var adapter =
          new AxizMessagingKafkaAutoConfiguration()
              .axizKafkaAsyncPublisherAdapter(template, sends)
              .publisher();
      assertTrue(adapter.supports("kafka:payments"));
      assertFalse(adapter.supports("https://sqs.example/queue"));
      var result =
          Mono.fromCompletionStage(() -> adapter.publish("kafka:payments", event))
              .subscribeOn(Schedulers.parallel())
              .toFuture();
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertFalse(result.isDone());
      release.countDown();
      ack.complete(
          new SendResult<>(
              record, new RecordMetadata(new TopicPartition("payments", 2), 42L, 0, 0L, 1, 0)));
      assertEquals("2-42", result.get(3, TimeUnit.SECONDS).brokerMessageId());
      assertTrue(BlockHoundExtension.detectedBlockingCalls().isEmpty());
    } finally {
      release.countDown();
    }
  }

  @Test
  void disabledMessagingDoesNotCreateKafkaResources() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizMessagingKafkaAutoConfiguration.class))
        .withPropertyValues(
            "axiz.messaging.enabled=false", "axiz.messaging.kafka.bootstrap-servers=127.0.0.1:1")
        .run(
            ctx ->
                Assertions.assertThat(ctx)
                    .hasNotFailed()
                    .doesNotHaveBean(BoundedAsyncDispatcher.class)
                    .doesNotHaveBean(AsyncEventPublisherAdapter.class)
                    .doesNotHaveBean(KafkaProducer.class));
  }
}
