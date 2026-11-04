package pe.axiz.spring.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;
import pe.axiz.adapter.messaging.kafka.KafkaEnvelopes;
import pe.axiz.adapter.messaging.kafka.KafkaEventPublisher;
import pe.axiz.adapter.messaging.kafka.MskIam;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PayloadException;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.spring.foundation.BoundedAsyncDispatcher;

/**
 * Amazon MSK: IAM-authenticated idempotent producer for {@code kafka:} destinations, and listener
 * containers for {@code axiz.messaging.kafka.topics} with exponential redelivery and a dead-letter
 * topic. Enabled when bootstrap servers are configured.
 */
@AutoConfiguration
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.messaging.kafka.KafkaEventPublisher",
      "org.springframework.kafka.core.KafkaTemplate"
    })
@ConditionalOnExpression(
    "'${axiz.messaging.kafka.bootstrap-servers:${spring.kafka.bootstrap-servers:}}' != ''")
@ConditionalOnBooleanProperty(name = "axiz.messaging.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizMessagingProperties.class)
public class AxizMessagingKafkaAutoConfiguration {

  static Map<String, Object> common(AxizMessagingProperties p, Environment env) {
    Map<String, Object> props = new HashMap<>();
    String servers =
        p.kafka().bootstrapServers() != null
            ? p.kafka().bootstrapServers()
            : env.getProperty("spring.kafka.bootstrap-servers");
    props.put("bootstrap.servers", Objects.requireNonNull(servers));
    props.put("client.id", env.getProperty("spring.application.name", "application"));
    if (p.kafka().iam()) {
      props.putAll(MskIam.clientProperties());
    }
    return props;
  }

  @Bean(destroyMethod = "close")
  KafkaProducer<String, byte[]> axizKafkaProducer(AxizMessagingProperties p, Environment env) {
    Map<String, Object> props = common(p, env);
    props.putAll(MskIam.reliableProducer());
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, p.kafka().sendTimeout().toMillis());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return new KafkaProducer<>(props);
  }

  @Bean
  EventPublisherAdapter axizKafkaPublisherAdapter(
      KafkaProducer<String, byte[]> producer, AxizMessagingProperties p) {
    return new EventPublisherAdapter(
        "kafka", new KafkaEventPublisher(producer, p.kafka().sendTimeout()));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  BoundedAsyncDispatcher axizKafkaSendDispatcher(
      AxizMessagingProperties p, Environment env, ObjectProvider<MeterRegistry> registries) {
    return new BoundedAsyncDispatcher(
        "kafka-events",
        env.getProperty("axiz.messaging.kafka.send-workers", Integer.class, 2),
        env.getProperty("axiz.messaging.kafka.send-queue-capacity", Integer.class, 256),
        p.kafka().sendTimeout(),
        registries.getIfAvailable(() -> Metrics.globalRegistry));
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  AsyncEventPublisherAdapter axizKafkaAsyncPublisherAdapter(
      @Qualifier("axizAsyncKafkaTemplate") KafkaTemplate<String, byte[]> template,
      @Qualifier("axizKafkaSendDispatcher") BoundedAsyncDispatcher sends) {
    return new AsyncEventPublisherAdapter(
        "kafka",
        new AsyncEventPublisher() {
          @Override
          public boolean supports(String destination) {
            return destination.startsWith(KafkaEventPublisher.SCHEME)
                || (!destination.startsWith("https://") && !destination.startsWith("arn:"));
          }

          @Override
          public java.util.concurrent.CompletionStage<PublishResult> publish(
              String destination, EventEnvelope event) {
            return sends.submit(
                () ->
                    template
                        .send(
                            pe.axiz.adapter.messaging.kafka.KafkaEventRecords.create(
                                destination, event))
                        .thenApply(
                            sent ->
                                new PublishResult(
                                    event.id(),
                                    sent.getRecordMetadata().topic(),
                                    sent.getRecordMetadata().partition()
                                        + "-"
                                        + sent.getRecordMetadata().offset())));
          }
        });
  }

  @Bean(destroyMethod = "destroy")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  KafkaTemplate<String, byte[]> axizAsyncKafkaTemplate(AxizMessagingProperties p, Environment env) {
    Map<String, Object> props = common(p, env);
    props.putAll(MskIam.reliableProducer());
    props.put(ProducerConfig.CLIENT_ID_CONFIG, props.get("client.id") + "-reactive");
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, p.kafka().sendTimeout().toMillis());
    var factory =
        new DefaultKafkaProducerFactory<String, byte[]>(
            props, new StringSerializer(), new ByteArraySerializer());
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

  /** Listener containers, one per configured topic. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnListProperty("axiz.messaging.kafka.topics")
  static class ConsumerConfiguration {

    @Bean
    KafkaConsumers axizKafkaConsumers(
        AxizMessagingProperties p, Environment env, ObservedDispatcher dispatcher) {
      Map<String, Object> consumer = common(p, env);
      consumer.put(
          ConsumerConfig.GROUP_ID_CONFIG,
          p.kafka().groupId() != null
              ? p.kafka().groupId()
              : env.getProperty("spring.application.name", "application"));
      consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      consumer.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
      DefaultKafkaConsumerFactory<String, byte[]> consumers =
          new DefaultKafkaConsumerFactory<>(
              consumer, new StringDeserializer(), new ByteArrayDeserializer());
      Map<String, Object> producer = common(p, env);
      producer.putAll(MskIam.reliableProducer());
      KafkaTemplate<String, byte[]> dlqTemplate =
          new KafkaTemplate<>(
              new DefaultKafkaProducerFactory<>(
                  producer, new StringSerializer(), new ByteArraySerializer()));
      String suffix = p.kafka().deadLetterSuffix();
      DeadLetterPublishingRecoverer recoverer =
          new DeadLetterPublishingRecoverer(
              dlqTemplate, (record, ex) -> new TopicPartition(record.topic() + suffix, -1));
      AxizMessagingProperties.Consumer c = p.consumer();
      ExponentialBackOffWithMaxRetries backOff =
          new ExponentialBackOffWithMaxRetries(Math.max(0, c.maxAttempts() - 1));
      backOff.setInitialInterval(c.initialDelay().toMillis());
      backOff.setMultiplier(2.0);
      backOff.setMaxInterval(c.maxDelay().toMillis());
      return new KafkaConsumers(
          p.kafka().topics().stream()
              .map(
                  topic -> {
                    ContainerProperties props = new ContainerProperties(topic);
                    props.setObservationEnabled(false);
                    props.setDeliveryAttemptHeader(true);
                    props.setShutdownTimeout(Duration.ofSeconds(30).toMillis());
                    props.setMessageListener(
                        (MessageListener<String, byte[]>) record -> handle(dispatcher, record));
                    DefaultErrorHandler errors = new DefaultErrorHandler(recoverer, backOff);
                    // busy records wait for the inbox lease instead of using the retry budget
                    errors.setBackOffFunction((rec, ex) -> deferred(ex) ? deferBackOff(p) : null);
                    errors.setResetStateOnExceptionChange(true);
                    errors.addNotRetryableExceptions(
                        PayloadException.class, DeadLetterException.class);
                    ConcurrentMessageListenerContainer<String, byte[]> container =
                        new ConcurrentMessageListenerContainer<>(consumers, props);
                    container.setConcurrency(p.kafka().concurrency());
                    container.setCommonErrorHandler(errors);
                    container.setBeanName("axiz-kafka-" + topic);
                    return container;
                  })
              .toList());
    }

    static void handle(ObservedDispatcher dispatcher, ConsumerRecord<String, byte[]> record) {
      org.apache.kafka.common.header.Header h =
          record.headers().lastHeader("kafka_deliveryAttempt");
      int attempt =
          h != null && h.value().length == 4 ? java.nio.ByteBuffer.wrap(h.value()).getInt() : 1;
      EventDispatcher.Outcome outcome =
          dispatcher.dispatch(record.topic(), KafkaEnvelopes.from(record), attempt);
      String where = record.topic() + "@" + record.offset();
      // exhaustive: a new decision must say how the container treats the record
      @Nullable RuntimeException failure =
          switch (outcome.decision()) {
            case ACK -> null;
            case RETRY -> new RetryLaterException(where);
            case DEFER -> new DeferredException(where);
            case DEAD_LETTER -> new DeadLetterException(where);
          };
      if (failure != null) {
        throw failure;
      }
    }
  }

  static boolean deferred(@Nullable Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof DeferredException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Fixed back-off of busy records: every {@code axiz.messaging.consumer.defer-interval} for up to
   * {@code defer-max-wait} (longer than the inbox lease), then the record follows the failure path
   * (dead-letter topic).
   */
  static FixedBackOff deferBackOff(AxizMessagingProperties p) {
    AxizMessagingProperties.Consumer c = p.consumer();
    long interval = Math.max(1000, c.deferInterval().toMillis());
    long attempts = Math.max(1, c.deferMaxWait().toMillis() / interval);
    return new FixedBackOff(interval, attempts);
  }

  /** Signals a transient failure: the error handler redelivers with backoff. */
  static final class RetryLaterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RetryLaterException(String where) {
      super("Redelivery requested for " + where);
    }
  }

  /**
   * Another delivery holds the record (inbox lease): it is redelivered with its own fixed back-off,
   * long enough to outlive the lease, and never sent to the dead-letter topic for being busy.
   */
  static final class DeferredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeferredException(String where) {
      super("Held by another delivery: " + where);
    }
  }

  /** Signals a permanent failure: the record goes straight to the dead-letter topic. */
  static final class DeadLetterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeadLetterException(String where) {
      super("Dead-lettering " + where);
    }
  }
}
