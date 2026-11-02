package pe.axiz.adapter.audit.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;

class KafkaAuditPublisherTest {

  private static AuditEvent event(String resourceId) {
    return new AuditEvent(
        "e-1",
        Instant.EPOCH,
        "svc",
        "order.create",
        "order",
        resourceId,
        AuditEvent.Actor.system(),
        AuditEvent.Outcome.SUCCESS,
        null,
        "c-1",
        "web",
        null,
        Map.of("k", "v"));
  }

  @Test
  void sendsKeyedJsonRecordAndWaitsForAck() {
    MockProducer<String, String> producer =
        new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    new KafkaAuditPublisher(producer, "audit", Duration.ofSeconds(1)).publish(event("o-1"));
    assertEquals(1, producer.history().size());
    var record = producer.history().get(0);
    assertEquals("order:o-1", record.key());
    assertTrue(record.value().contains("\"action\":\"order.create\""));
    assertEquals("e-1", new String(record.headers().lastHeader("ce_id").value()));
  }

  @Test
  void failureIsVisible() {
    MockProducer<String, String> producer =
        new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
    KafkaAuditPublisher publisher =
        new KafkaAuditPublisher(producer, "audit", Duration.ofMillis(50));
    assertThrows(AuditPublishException.class, () -> publisher.publish(event(null)));
  }
}
