package pe.axiz.core.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishException;

class KafkaMessagingTest {

  private static EventEnvelope event() {
    return new EventEnvelope(
        "e-1",
        "urn:axiz:test",
        "t.v1",
        Instant.parse("2026-01-01T00:00:00Z"),
        "agg-1",
        "application/json",
        "{}".getBytes(StandardCharsets.UTF_8),
        Map.of("correlationid", "c-1"));
  }

  @Test
  void publishesBinaryCloudEventKeyedBySubject() {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventPublisher publisher = new KafkaEventPublisher(producer, Duration.ofSeconds(1));
    assertTrue(publisher.supports("payments.events"));
    assertFalse(publisher.supports("https://sqs.us-east-1.amazonaws.com/1/q"));
    assertFalse(publisher.supports("arn:aws:sns:us-east-1:1:t"));
    var result = publisher.publish("payments.events", event());
    assertEquals("e-1", result.eventId());
    ProducerRecord<String, byte[]> sent = producer.history().get(0);
    assertEquals("agg-1", sent.key());
    assertEquals(
        "t.v1", new String(sent.headers().lastHeader("ce_type").value(), StandardCharsets.UTF_8));

    ConsumerRecord<String, byte[]> consumed =
        new ConsumerRecord<>("payments.events", 0, 0L, sent.key(), sent.value());
    sent.headers().forEach(h -> consumed.headers().add(h));
    EventEnvelope back = KafkaEnvelopes.from(consumed);
    assertEquals(event(), back);
    assertEquals("c-1", back.correlationId().orElseThrow());
  }

  @Test
  void theKafkaSchemeOfADestinationIsNotPartOfTheTopic() {
    // axiz.messaging.destinations documents kafka:<topic>; "kafka:payments" is not a valid topic
    MockProducer<String, byte[]> producer =
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventPublisher publisher = new KafkaEventPublisher(producer, Duration.ofSeconds(1));
    assertTrue(publisher.supports("kafka:payments"));
    var result = publisher.publish("kafka:payments", event());
    assertEquals("payments", producer.history().get(0).topic());
    assertEquals("payments", result.destination());
  }

  @Test
  void unacknowledgedSendFails() {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer());
    assertThrows(
        PublishException.class,
        () -> new KafkaEventPublisher(producer, Duration.ofMillis(50)).publish("t", event()));
  }

  @Test
  void mskIamProperties() {
    assertEquals("AWS_MSK_IAM", MskIam.clientProperties().get("sasl.mechanism"));
    assertEquals("all", MskIam.reliableProducer().get("acks"));
  }
}
