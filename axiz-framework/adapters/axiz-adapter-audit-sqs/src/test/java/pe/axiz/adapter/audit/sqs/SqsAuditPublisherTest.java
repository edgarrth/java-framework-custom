package pe.axiz.adapter.audit.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsAuditPublisherTest {

  static final class FakeSqs implements SqsClient {
    final List<SendMessageRequest> sent = new ArrayList<>();
    boolean fail;

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest request) {
      if (fail) {
        throw SqsException.builder().message("down").build();
      }
      sent.add(request);
      return SendMessageResponse.builder().messageId("m-1").build();
    }

    @Override
    public String serviceName() {
      return "sqs";
    }

    @Override
    public void close() {}
  }

  private static AuditEvent event() {
    return new AuditEvent(
        "e-1",
        Instant.EPOCH,
        "svc",
        "order.create",
        "order",
        "o-1",
        AuditEvent.Actor.system(),
        AuditEvent.Outcome.SUCCESS,
        null,
        null,
        null,
        null,
        Map.of());
  }

  @Test
  void fifoUsesDeterministicDeduplicationId() {
    FakeSqs sqs = new FakeSqs();
    new SqsAuditPublisher(sqs, "https://sqs.local/000/audit.fifo").publish(event());
    SendMessageRequest r = sqs.sent.get(0);
    assertEquals("e-1", r.messageDeduplicationId());
    assertEquals("order:o-1", r.messageGroupId());
    assertEquals("e-1", r.messageAttributes().get("ce_id").stringValue());
  }

  @Test
  void standardQueueHasNoGroup() {
    FakeSqs sqs = new FakeSqs();
    new SqsAuditPublisher(sqs, "https://sqs.local/000/audit").publish(event());
    assertNull(sqs.sent.get(0).messageGroupId());
  }

  @Test
  void failureIsVisible() {
    FakeSqs sqs = new FakeSqs();
    sqs.fail = true;
    assertThrows(
        AuditPublishException.class, () -> new SqsAuditPublisher(sqs, "q").publish(event()));
  }

  @Test
  void bodyIsAStructuredCloudEvent() {
    FakeSqs sqs = new FakeSqs();
    new SqsAuditPublisher(sqs, "q").publish(event());
    var body = pe.axiz.core.commons.json.JsonReader.parseObject(sqs.sent.get(0).messageBody());
    assertEquals("1.0", body.get("specversion"));
    assertEquals("e-1", body.get("id"));
    assertEquals("pe.axiz.audit.event.v1", body.get("type"));
    assertEquals("application/json", body.get("datacontenttype"));
    assertEquals(
        "order",
        ((java.util.Map<?, ?>) java.util.Objects.requireNonNull(body.get("data")))
            .get("resourceType"));
  }
}
