package pe.axiz.adapter.audit.kafka;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditPublisher;

/**
 * Publishes audit events to an Amazon MSK (Kafka) topic. The record key is the resource so the
 * history of a resource stays ordered in one partition. Every send waits for the broker
 * acknowledgement (bounded by a timeout) so failures are visible; wrap it with the asynchronous
 * publisher to keep callers non-blocking.
 */
public final class KafkaAuditPublisher implements AuditPublisher {

  private final Producer<String, String> producer;
  private final String topic;
  private final Duration timeout;

  public KafkaAuditPublisher(Producer<String, String> producer, String topic, Duration timeout) {
    this.producer = Objects.requireNonNull(producer);
    this.topic = Objects.requireNonNull(topic);
    if (topic.isBlank()) throw new IllegalArgumentException("Audit Kafka topic must not be blank");
    this.timeout = Objects.requireNonNull(timeout);
  }

  @Override
  public void publish(AuditEvent event) {
    ProducerRecord<String, String> record = KafkaAuditRecords.create(topic, event);
    try {
      producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuditPublishException("Interrupted while publishing audit event " + event.id(), e);
    } catch (ExecutionException | TimeoutException e) {
      throw new AuditPublishException(
          "Audit event " + event.id() + " not acknowledged by " + topic, e);
    }
  }
}
