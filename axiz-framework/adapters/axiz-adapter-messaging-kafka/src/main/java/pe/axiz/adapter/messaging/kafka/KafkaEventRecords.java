package pe.axiz.adapter.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import pe.axiz.core.messaging.CloudEventHeaders;
import pe.axiz.core.messaging.EventEnvelope;

/** Binary CloudEvents encoding shared by imperative and asynchronous Kafka publication. */
public final class KafkaEventRecords {
  private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");

  private KafkaEventRecords() {}

  /** Kafka topic syntax, shared by imperative/native routing; never claims URLs or placeholders. */
  public static boolean isDestination(String destination) {
    String topic =
        destination.startsWith(KafkaEventPublisher.SCHEME)
            ? destination.substring(KafkaEventPublisher.SCHEME.length())
            : destination;
    return !topic.equals(".") && !topic.equals("..") && TOPIC.matcher(topic).matches();
  }

  /** Binary CloudEvent record shared by synchronous and asynchronous publication. */
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
