package pe.axiz.adapter.messaging.kafka;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import pe.axiz.core.messaging.BoundedPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishBound;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;

/**
 * Publishes CloudEvents (binary mode) to Amazon MSK. The key is the subject (aggregate) so events
 * of the same aggregate stay ordered; each send waits for the broker acknowledgement. Destinations
 * are {@code kafka:<topic>} (the documented form of {@code axiz.messaging.destinations}) or a bare
 * topic name (anything that is not an SQS URL or an SNS ARN).
 */
public final class KafkaEventPublisher implements EventPublisher, BoundedPublisher {

  /** Scheme of Kafka destinations: {@code kafka:payments} is the topic {@code payments}. */
  public static final String SCHEME = "kafka:";

  private final Producer<String, byte[]> producer;
  private final Duration timeout;
  private final PublishBound bound;

  public KafkaEventPublisher(Producer<String, byte[]> producer, Duration timeout) {
    this.producer = Objects.requireNonNull(producer);
    this.timeout = Objects.requireNonNull(timeout);
    if (!timeout.isPositive()) throw new IllegalArgumentException("send timeout must be positive");
    this.bound = PublishBound.unbounded();
  }

  /** Uses the effective max.block.ms of the same producer, plus the acknowledgement wait. */
  public KafkaEventPublisher(
      Producer<String, byte[]> producer, Duration maxBlock, Duration timeout) {
    this.producer = Objects.requireNonNull(producer);
    this.timeout = Objects.requireNonNull(timeout);
    if (maxBlock.isNegative() || !timeout.isPositive())
      throw new IllegalArgumentException("maxBlock must be non-negative and timeout positive");
    this.bound = PublishBound.of(maxBlock.plus(timeout));
  }

  @Override
  public PublishBound publishBound() {
    return bound;
  }

  @Override
  public boolean supports(String destination) {
    return destination.startsWith(SCHEME)
        || (!destination.startsWith("https://") && !destination.startsWith("arn:"));
  }

  /** The topic of a destination, without the {@code kafka:} scheme. */
  static String topic(String destination) {
    return destination.startsWith(SCHEME) ? destination.substring(SCHEME.length()) : destination;
  }

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    ProducerRecord<String, byte[]> record = KafkaEventRecords.create(destination, event);
    String topic = record.topic();
    try {
      RecordMetadata md = producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return new PublishResult(event.id(), topic, md.partition() + "-" + md.offset());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublishException("Interrupted publishing " + event.id(), e);
    } catch (ExecutionException | TimeoutException e) {
      throw new PublishException("Event " + event.id() + " not acknowledged by topic " + topic, e);
    }
  }
}
