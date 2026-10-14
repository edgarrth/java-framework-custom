package pe.axiz.spring.audit;

import org.apache.kafka.clients.producer.Producer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ProducerFactory;
import pe.axiz.core.audit.kafka.KafkaAuditPublisher;

/**
 * MSK sink for audit events, active when {@code axiz.audit.kafka.topic} is set. It reuses the
 * application's Kafka producer factory (IAM authentication is configured by the messaging starter).
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.core.audit.kafka.KafkaAuditPublisher",
      "org.springframework.kafka.core.ProducerFactory"
    })
@ConditionalOnProperty("axiz.audit.kafka.topic")
@ConditionalOnBean(ProducerFactory.class)
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizAuditKafkaAutoConfiguration {

  @Bean
  @SuppressWarnings("unchecked")
  AuditSink axizKafkaAuditSink(ProducerFactory<?, ?> producers, AxizAuditProperties properties) {
    Producer<String, String> producer = (Producer<String, String>) producers.createProducer();
    String topic = java.util.Objects.requireNonNull(properties.kafka().topic());
    return new AuditSink(
        "kafka", new KafkaAuditPublisher(producer, topic, properties.kafka().timeout()));
  }
}
