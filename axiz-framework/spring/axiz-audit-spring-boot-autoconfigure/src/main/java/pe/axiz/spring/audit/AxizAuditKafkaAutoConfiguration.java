package pe.axiz.spring.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Objects;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import pe.axiz.adapter.audit.kafka.KafkaAuditPublisher;
import pe.axiz.adapter.audit.kafka.KafkaAuditRecords;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.spring.foundation.BoundedAsyncDispatcher;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.kafka.KafkaTransportSecurity;

/**
 * MSK sink for audit events, active when {@code axiz.audit.kafka.topic} is set. It reuses the
 * application's Kafka producer factory (IAM authentication is configured by the messaging starter).
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.audit.kafka.KafkaAuditPublisher",
      "org.springframework.kafka.core.ProducerFactory"
    })
@ConditionalOnConfiguredProperty("axiz.audit.kafka.topic")
@ConditionalOnBean(ProducerFactory.class)
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizAuditProperties.class)
public class AxizAuditKafkaAutoConfiguration {

  @Bean
  @SuppressWarnings("unchecked")
  @ConditionalOnImperativeApplication
  AuditSink axizKafkaAuditSink(
      ProducerFactory<?, ?> producers, AxizAuditProperties properties, Environment env) {
    KafkaTransportSecurity.validate(
        producers.getConfigurationProperties(), DataTransportSecurityPolicy.from(env::getProperty));
    Producer<String, String> producer = (Producer<String, String>) producers.createProducer();
    String topic = Objects.requireNonNull(properties.kafka().topic());
    return new AuditSink(
        "kafka", new KafkaAuditPublisher(producer, topic, properties.kafka().timeout()));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  BoundedAsyncDispatcher axizKafkaAuditDispatcher(
      AxizAuditProperties p, Environment env, ObjectProvider<MeterRegistry> registries) {
    return new BoundedAsyncDispatcher(
        "kafka-audit",
        env.getProperty("axiz.audit.kafka.send-workers", Integer.class, 2),
        env.getProperty("axiz.audit.kafka.send-queue-capacity", Integer.class, 256),
        p.kafka().timeout(),
        registries.getIfAvailable());
  }

  @Bean
  @SuppressWarnings("unchecked")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  AsyncAuditSink axizKafkaAsyncAuditSink(
      @Qualifier("axizAsyncAuditKafkaTemplate") KafkaTemplate<String, String> template,
      AxizAuditProperties p,
      @Qualifier("axizKafkaAuditDispatcher") BoundedAsyncDispatcher sends) {
    return new AsyncAuditSink(
        "kafka",
        event ->
            sends.submit(
                () ->
                    template
                        .send(
                            KafkaAuditRecords.create(
                                Objects.requireNonNull(p.kafka().topic()), event))
                        .thenApply(sent -> (Void) null)));
  }

  @Bean(destroyMethod = "destroy")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  KafkaTemplate<String, String> axizAsyncAuditKafkaTemplate(
      ProducerFactory<?, ?> producers, AxizAuditProperties p, Environment env) {
    var properties = new HashMap<String, Object>(producers.getConfigurationProperties());
    properties.put(
        ProducerConfig.CLIENT_ID_CONFIG,
        properties.getOrDefault("client.id", "axiz") + "-audit-reactive");
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, p.kafka().timeout().toMillis());
    KafkaTransportSecurity.validate(properties, DataTransportSecurityPolicy.from(env::getProperty));
    var factory =
        new DefaultKafkaProducerFactory<String, String>(
            properties, new StringSerializer(), new StringSerializer());
    return new KafkaTemplate<>(factory) {
      @Override
      public void destroy() {
        try {
          super.destroy();
        } finally {
          factory.destroy();
        }
      }
    };
  }
}
