package pe.axiz.spring.audit.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Objects;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
import pe.axiz.spring.audit.AsyncAuditSink;
import pe.axiz.spring.audit.AsyncStrictAuditSink;
import pe.axiz.spring.audit.AuditSink;
import pe.axiz.spring.audit.StrictAuditSink;
import pe.axiz.spring.foundation.BoundedAsyncDispatcher;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.kafka.KafkaTransportSecurity;

/**
 * Kafka audit transport with owned producers and bounded, idempotent all-replica acknowledgement.
 * Authentication properties come from the configured producer factory; its settings are not
 * mutated.
 */
@AutoConfiguration(
    beforeName = {
      "pe.axiz.spring.audit.AxizAuditAutoConfiguration",
      "pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration"
    },
    afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.audit.kafka.KafkaAuditPublisher",
      "org.springframework.kafka.core.ProducerFactory"
    })
@ConditionalOnConfiguredProperty("axiz.audit.kafka.topic")
@ConditionalOnBooleanProperty(name = "axiz.audit.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizAuditKafkaProperties.class)
public class AxizAuditKafkaAutoConfiguration {
  /** Bind and validate destination settings before constructing transport resources. */
  public AxizAuditKafkaAutoConfiguration(AxizAuditKafkaProperties properties) {
    Objects.requireNonNull(properties);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnImperativeApplication
  ProducerResources axizAuditKafkaResources(
      ProducerFactory<?, ?> producers, AxizAuditKafkaProperties properties, Environment env) {
    return new ProducerResources(auditFactory(producers, properties, env, "imperative"));
  }

  @Bean
  @ConditionalOnImperativeApplication
  AuditSink axizKafkaAuditSink(ProducerResources resources, AxizAuditKafkaProperties properties) {
    return new AuditSink(
        "kafka",
        new KafkaAuditPublisher(
            resources.producer, Objects.requireNonNull(properties.topic()), properties.timeout()));
  }

  @Bean
  @ConditionalOnImperativeApplication
  StrictAuditSink axizKafkaStrictAuditSink(@Qualifier("axizKafkaAuditSink") AuditSink sink) {
    return new StrictAuditSink("kafka", sink.publisher());
  }

  static final class ProducerResources implements AutoCloseable {
    private final DefaultKafkaProducerFactory<String, String> factory;
    final Producer<String, String> producer;

    ProducerResources(DefaultKafkaProducerFactory<String, String> factory) {
      this.factory = factory;
      try {
        this.producer = factory.createProducer();
      } catch (RuntimeException | Error failure) {
        factory.destroy();
        throw failure;
      }
    }

    @Override
    public void close() {
      factory.destroy();
    }
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  BoundedAsyncDispatcher axizKafkaAuditDispatcher(
      AxizAuditKafkaProperties p, Environment env, ObjectProvider<MeterRegistry> registries) {
    return new BoundedAsyncDispatcher(
        "kafka-audit",
        env.getProperty("axiz.audit.kafka.send-workers", Integer.class, 2),
        env.getProperty("axiz.audit.kafka.send-queue-capacity", Integer.class, 256),
        p.timeout(),
        registries.getIfAvailable());
  }

  @Bean
  @SuppressWarnings("unchecked")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  AsyncAuditSink axizKafkaAsyncAuditSink(
      @Qualifier("axizAsyncAuditKafkaTemplate") KafkaTemplate<String, String> template,
      AxizAuditKafkaProperties p,
      @Qualifier("axizKafkaAuditDispatcher") BoundedAsyncDispatcher sends) {
    return new AsyncAuditSink(
        "kafka",
        event ->
            sends.submit(
                () ->
                    template
                        .send(KafkaAuditRecords.create(Objects.requireNonNull(p.topic()), event))
                        .thenApply(sent -> (Void) null)));
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  AsyncStrictAuditSink axizKafkaAsyncStrictAuditSink(
      @Qualifier("axizKafkaAsyncAuditSink") AsyncAuditSink sink) {
    return new AsyncStrictAuditSink("kafka", sink.publisher());
  }

  @Bean(destroyMethod = "destroy")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  KafkaTemplate<String, String> axizAsyncAuditKafkaTemplate(
      ProducerFactory<?, ?> producers, AxizAuditKafkaProperties p, Environment env) {
    var factory = auditFactory(producers, p, env, "reactive");
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

  static DefaultKafkaProducerFactory<String, String> auditFactory(
      ProducerFactory<?, ?> producers, AxizAuditKafkaProperties p, Environment env, String lane) {
    var properties = new HashMap<String, Object>(producers.getConfigurationProperties());
    properties.put(
        ProducerConfig.CLIENT_ID_CONFIG,
        properties.getOrDefault("client.id", "axiz") + "-audit-" + lane);
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, p.timeout().toMillis());
    properties.put(
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.toIntExact(p.timeout().toMillis()));
    properties.put(
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Math.toIntExact(p.timeout().toMillis()));
    properties.put(ProducerConfig.LINGER_MS_CONFIG, 0L);
    KafkaTransportSecurity.validate(properties, DataTransportSecurityPolicy.from(env::getProperty));
    return new DefaultKafkaProducerFactory<>(
        properties, new StringSerializer(), new StringSerializer());
  }
}
