package pe.axiz.adapter.audit.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.json.JsonWriter;

/**
 * Codificación binaria de CloudEvents compartida por la publicación Kafka imperativa y asíncrona.
 */
public final class KafkaAuditRecords {
  private KafkaAuditRecords() {}

  /**
   * Registro CloudEvent binario compartido por la publicación de auditoría síncrona y asíncrona.
   */
  public static ProducerRecord<String, String> create(String topic, AuditEvent event) {
    String key =
        event.resourceType() + ":" + (event.resourceId() == null ? event.id() : event.resourceId());
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, key, JsonWriter.writeCanonical(event.toMap()));
    // CloudEvent en modo binario: cabeceras ce_* y los datos JSON como valor
    record.headers().add("content-type", "application/json".getBytes(StandardCharsets.UTF_8));
    event
        .cloudEventAttributes()
        .forEach((k, v) -> record.headers().add("ce_" + k, v.getBytes(StandardCharsets.UTF_8)));
    return record;
  }
}
