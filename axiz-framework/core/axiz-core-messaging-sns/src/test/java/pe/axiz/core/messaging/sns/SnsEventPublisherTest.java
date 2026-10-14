package pe.axiz.core.messaging.sns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

class SnsEventPublisherTest {

  @Test
  void publishesWithFilterAttributeAndFifoIds() {
    List<PublishRequest> sent = new ArrayList<>();
    boolean[] fail = {false};
    SnsClient sns =
        new SnsClient() {
          @Override
          public PublishResponse publish(PublishRequest r) {
            if (fail[0]) {
              throw SnsException.builder().message("x").build();
            }
            sent.add(r);
            return PublishResponse.builder().messageId("m").build();
          }

          @Override
          public String serviceName() {
            return "sns";
          }

          @Override
          public void close() {}
        };
    EventEnvelope e =
        new EventEnvelope(
            "e-1",
            "urn:s",
            "t.v1",
            Instant.EPOCH,
            null,
            "application/json",
            "{}".getBytes(StandardCharsets.UTF_8),
            Map.of());
    SnsEventPublisher p = new SnsEventPublisher(sns);
    String fifo = "arn:aws:sns:us-east-1:123:payments.fifo";
    assertTrue(p.supports(fifo));
    assertFalse(p.supports("https://sqs.x/q"));
    p.publish(fifo, e);
    assertEquals("t.v1", sent.get(0).messageGroupId());
    assertEquals("e-1", sent.get(0).messageDeduplicationId());
    assertEquals("t.v1", sent.get(0).messageAttributes().get("ce_type").stringValue());
    assertEquals(e, CloudEventJson.read(sent.get(0).message()));
    fail[0] = true;
    assertThrows(PublishException.class, () -> p.publish(fifo, e));
  }
}
