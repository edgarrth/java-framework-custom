package pe.axiz.adapter.audit.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.commons.json.JsonWriter;

/** Binary CloudEvents encoding shared by imperative and asynchronous Kafka publication. */
public final class KafkaAuditRecords {
  private KafkaAuditRecords() {}

  /** Binary CloudEvent record shared by synchronous and asynchronous audit publication. */
  public static ProducerRecord<String, String> create(String topic, AuditEvent event) {
    String key =
        event.resourceType() + ":" + (event.resourceId() == null ? event.id() : event.resourceId());
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, key, JsonWriter.write(event.toMap()));
    // binary-mode CloudEvent: ce_* headers and the JSON data as value
    record.headers().add("content-type", "application/json".getBytes(StandardCharsets.UTF_8));
    event
        .cloudEventAttributes()
        .forEach((k, v) -> record.headers().add("ce_" + k, v.getBytes(StandardCharsets.UTF_8)));
    return record;
  }
}
