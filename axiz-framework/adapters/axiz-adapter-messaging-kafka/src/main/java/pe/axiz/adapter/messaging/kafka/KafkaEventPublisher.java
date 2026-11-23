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
 * Publica CloudEvents (modo binario) en Amazon MSK. La clave es el subject (agregado), de modo que
 * los eventos de un mismo agregado se mantienen ordenados; cada envío espera la confirmación del
 * broker. Los destinos son {@code kafka:<topic>} (la forma documentada de {@code
 * axiz.messaging.destinations}) o un nombre de tópico simple (cualquier cosa que no sea una URL de
 * SQS ni un ARN de SNS).
 */
public final class KafkaEventPublisher implements EventPublisher, BoundedPublisher {

  /** Esquema de los destinos Kafka: {@code kafka:payments} es el tópico {@code payments}. */
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

  /** Usa el max.block.ms efectivo del mismo productor, más la espera de confirmación. */
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
    return KafkaEventRecords.isDestination(destination);
  }

  /** Tópico de un destino, sin el esquema {@code kafka:}. */
  static String topic(String destination) {
    if (!KafkaEventRecords.isDestination(destination))
      throw new IllegalArgumentException("Invalid Kafka destination");
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
