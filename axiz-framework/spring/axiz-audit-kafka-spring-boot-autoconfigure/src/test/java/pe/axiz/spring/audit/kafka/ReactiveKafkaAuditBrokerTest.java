package pe.axiz.spring.audit.kafka;

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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.spring.audit.AsyncAuditSink;
import pe.axiz.spring.audit.AuditSink;
import pe.axiz.spring.audit.AxizAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizStrictAuditAutoConfiguration;
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
                  AxizNativeAuditAutoConfiguration.class,
                  AxizStrictAuditAutoConfiguration.class))
          .withPropertyValues(
              "axiz.config.environment=test",
              "axiz.transport.allow-insecure-data-stores=true",
              "axiz.audit.mode=ASYNC",
              "axiz.audit.strict.enabled=true",
              "axiz.audit.strict.timeout=10s",
              "axiz.audit.kafka.topic=audit",
              "axiz.audit.kafka.timeout=10s")
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx)
                    .hasSingleBean(AsyncAuditSink.class)
                    .doesNotHaveBean(AuditSink.class);
                var event = event();
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
                  var normal = AuditEventJson.read(record.value());
                  StepVerifier.create(
                          Mono.fromCompletionStage(
                                  () -> ctx.getBean(AsyncStrictAuditPublisher.class).publish(event))
                              .subscribeOn(Schedulers.parallel()))
                      .expectComplete()
                      .verify(Duration.ofSeconds(20));
                  var strict =
                      AuditEventJson.read(
                          KafkaTestUtils.getSingleRecord(consumer, "audit", Duration.ofSeconds(10))
                              .value());
                  assertEquals(normal.id(), strict.id());
                  assertThat(strict.integrity().chainId())
                      .isNotEqualTo(normal.integrity().chainId());
                  assertEquals(
                      AuditChainVerifier.IntegrityStatus.INTACT,
                      AuditChainVerifier.verify(List.of(strict), null, null).integrity());
                }
              });
      assertTrue(BlockHoundExtension.detectedBlockingCalls().isEmpty());
    } finally {
      factory.destroy();
      broker.destroy();
    }
  }

  private static AuditEvent event() {
    return AuditEvent.builder()
        .id("audit-1")
        .occurredAt(Instant.now())
        .service("svc")
        .action("payment")
        .resourceType("account")
        .resourceId("a-1")
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId("correlation")
        .channel("web")
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void imperativeStrictAuditOwnsReliableSettingsWithoutMutatingTheApplicationFactory() {
    var broker = new EmbeddedKafkaKraftBroker(1, 1, "strict-audit");
    broker.brokerListProperty("axiz.test.kafka-brokers-strict");
    broker.afterPropertiesSet();
    var original =
        Map.<String, Object>of(
            "bootstrap.servers",
            broker.getBrokersAsString(),
            "acks",
            "0",
            "enable.idempotence",
            false,
            "client.id",
            "application");
    var factory =
        new DefaultKafkaProducerFactory<String, String>(
            original, new StringSerializer(), new StringSerializer());
    try {
      new ApplicationContextRunner()
          .withBean(ProducerFactory.class, () -> factory)
          .withConfiguration(
              AutoConfigurations.of(
                  AxizAuditAutoConfiguration.class,
                  AxizAuditKafkaAutoConfiguration.class,
                  AxizStrictAuditAutoConfiguration.class))
          .withPropertyValues(
              "axiz.config.environment=test",
              "axiz.transport.allow-insecure-data-stores=true",
              "axiz.audit.mode=SYNC",
              "axiz.audit.strict.enabled=true",
              "axiz.audit.strict.timeout=10s",
              "axiz.audit.kafka.topic=strict-audit",
              "axiz.audit.kafka.timeout=10s")
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                ctx.getBean(StrictAuditPublisher.class).publish(event());
                assertEquals("0", factory.getConfigurationProperties().get("acks"));
                assertEquals(false, factory.getConfigurationProperties().get("enable.idempotence"));
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
                  consumer.subscribe(List.of("strict-audit"));
                  var stored =
                      AuditEventJson.read(
                          KafkaTestUtils.getSingleRecord(
                                  consumer, "strict-audit", Duration.ofSeconds(10))
                              .value());
                  assertEquals(
                      AuditChainVerifier.IntegrityStatus.INTACT,
                      AuditChainVerifier.verify(List.of(stored), null, null).integrity());
                }
              });
    } finally {
      factory.destroy();
      broker.destroy();
    }
  }
}
