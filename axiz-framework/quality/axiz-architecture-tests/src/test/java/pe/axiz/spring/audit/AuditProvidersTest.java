package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.spring.audit.kafka.AxizAuditKafkaAutoConfiguration;
import pe.axiz.spring.audit.sqs.AxizAuditSqsAutoConfiguration;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;

/** Which events {@code @Auditable} and the HTTP audit record, and how they are delivered. */
class AuditProvidersTest {
  @Test
  void kafkaAndSqsSinksAreAddedOnlyWhenConfigured() {
    ProducerFactory<String, String> producers =
        new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers", "localhost:9092"));
    new ApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditKafkaAutoConfiguration.class,
                AxizAuditSqsAutoConfiguration.class,
                AxizAuditAutoConfiguration.class))
        .withBean(ProducerFactory.class, () -> producers)
        .withPropertyValues(
            "axiz.audit.mode=ASYNC",
            "axiz.audit.kafka.topic=audit",
            "axiz.config.environment=test",
            "axiz.transport.allow-plain-http=true",
            "axiz.transport.allow-insecure-data-stores=true",
            "axiz.audit.sqs.queue-url=http://localhost:4566/000000000000/audit",
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=http://localhost:4566")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeansOfType(AuditSink.class).values().stream().map(AuditSink::name))
                  .containsExactlyInAnyOrder("kafka", "sqs");
            });
    new ApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(AuditTrail.class));
  }
}
