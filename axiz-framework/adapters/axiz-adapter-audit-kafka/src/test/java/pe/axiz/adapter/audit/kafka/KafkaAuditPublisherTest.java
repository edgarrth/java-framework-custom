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
import pe.axiz.core.commons.TransactionOutcome;

class KafkaAuditPublisherTest {

  private static AuditEvent event(String resourceId) {
    return AuditEvent.builder()
        .id("e-1")
        .occurredAt(Instant.EPOCH)
        .service("svc")
        .action("order.create")
        .resourceType("order")
        .resourceId(resourceId)
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId("c-1")
        .channel("web")
        .tenant(null)
        .details(Map.of("k", "v"))
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void emptyDestinationIsRejectedBeforePublication() {
    try (var client =
        new MockProducer<String, String>(
            true, null, new StringSerializer(), new StringSerializer())) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new KafkaAuditPublisher(client, " ", Duration.ofSeconds(1)));
    }
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
