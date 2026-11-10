package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(BlockHoundExtension.class)
class ReactiveKafkaAuditBrokerTest {
  @Test
  void coldReactiveAuditUsesAsyncSinkAndReachesBroker() {
    ReactiveInfrastructureBootstrap.initialize();
    var broker = new EmbeddedKafkaKraftBroker(1, 1, "audit");
    broker.brokerListProperty("axiz.test.kafka-brokers");
    broker.afterPropertiesSet();
    var factory =
        new DefaultKafkaProducerFactory<String, String>(
            Map.of(
                "bootstrap.servers",
                broker.getBrokersAsString(),
                "acks",
                "all",
                "enable.idempotence",
                true),
            new StringSerializer(),
            new StringSerializer());
    try {
      new ReactiveWebApplicationContextRunner()
          .withBean(ProducerFactory.class, () -> factory)
          .withConfiguration(
              AutoConfigurations.of(
                  AxizAuditAutoConfiguration.class,
                  AxizAuditKafkaAutoConfiguration.class,
                  AxizNativeAuditAutoConfiguration.class))
          .withPropertyValues("axiz.audit.kafka.topic=audit", "axiz.audit.kafka.timeout=10s")
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx)
                    .hasSingleBean(AsyncAuditSink.class)
                    .doesNotHaveBean(AuditSink.class);
                var event =
                    new AuditEvent(
                        "audit-1",
                        Instant.now(),
                        "svc",
                        "payment",
                        "account",
                        "a-1",
                        AuditEvent.Actor.system(),
                        AuditEvent.Outcome.SUCCESS,
                        null,
                        "correlation",
                        "web",
                        null,
                        Map.of());
                var publisher = ctx.getBean(AsyncAuditEventPublisher.class);
                StepVerifier.create(
                        Mono.fromCompletionStage(() -> publisher.publish(event))
                            .subscribeOn(Schedulers.parallel()))
                    .expectComplete()
                    .verify(Duration.ofSeconds(20));
                try (var consumer =
                    new KafkaConsumer<String, String>(
                        Map.of(
                            "bootstrap.servers",
                            broker.getBrokersAsString(),
                            "group.id",
                            UUID.randomUUID().toString(),
                            "auto.offset.reset",
                            "earliest"),
                        new StringDeserializer(),
                        new StringDeserializer())) {
                  consumer.subscribe(List.of("audit"));
                  var record =
                      KafkaTestUtils.getSingleRecord(consumer, "audit", Duration.ofSeconds(10));
                  assertEquals("account:a-1", record.key());
                  assertTrue(record.value().contains("audit-1"));
                }
              });
      assertTrue(BlockHoundExtension.detectedBlockingCalls().isEmpty());
    } finally {
      factory.destroy();
      broker.destroy();
    }
  }
}
