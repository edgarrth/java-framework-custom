package pe.axiz.adapter.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import pe.axiz.core.messaging.CloudEventHeaders;
import pe.axiz.core.messaging.EventEnvelope;

/**
 * Codificación binaria de CloudEvents compartida por la publicación Kafka imperativa y asíncrona.
 */
public final class KafkaEventRecords {
  private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");

  private KafkaEventRecords() {}

  /**
   * Sintaxis de tópicos Kafka, compartida por el enrutamiento imperativo y nativo; nunca reclama
   * URL ni marcadores de posición.
   */
  public static boolean isDestination(String destination) {
    String topic =
        destination.startsWith(KafkaEventPublisher.SCHEME)
            ? destination.substring(KafkaEventPublisher.SCHEME.length())
            : destination;
    return !topic.equals(".") && !topic.equals("..") && TOPIC.matcher(topic).matches();
  }

  /** Registro CloudEvent binario compartido por la publicación síncrona y asíncrona. */
  public static ProducerRecord<String, byte[]> create(String destination, EventEnvelope event) {
    String key = event.subject() != null ? event.subject() : event.id();
    String topic = KafkaEventPublisher.topic(destination);
    ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(topic, null, event.time().toEpochMilli(), key, event.data());
    CloudEventHeaders.toHeaders(event)
        .forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
    return record;
  }
}
