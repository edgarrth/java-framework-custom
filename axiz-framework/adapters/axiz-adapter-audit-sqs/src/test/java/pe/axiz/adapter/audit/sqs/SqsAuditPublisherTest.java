package pe.axiz.adapter.audit.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AttemptOutcome;
import pe.axiz.core.audit.AttemptRef;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.audit.InMemoryAuditChainState;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.json.JsonReader;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsAuditPublisherTest {

  @Test
  void retriesOfOneFactHaveDifferentFifoIdentitiesAndRetransmissionKeepsItsIdentity() {
    var state = new InMemoryAuditChainState(Clock.systemUTC(), 2);
    var sealer = new AuditSealer("svc/durable/instance", AuditSealer.Mode.HASH, state, null, null);
    var first = sealer.seal(event());
    var prior = first.integrity();
    var fallback =
        new AuditSealer("svc/fallback/instance", AuditSealer.Mode.HASH, state, null, null)
            .sealAgain(
                event(),
                new AttemptRef(
                    prior.chainId(), prior.sequence(), prior.hash(), AttemptOutcome.UNKNOWN));
    var sqs = new FakeSqs();
    var publisher = new SqsAuditPublisher(sqs, "https://sqs.local/000/audit.fifo");
    publisher.publish(first);
    publisher.publish(fallback);
    publisher.publish(first);
    assertNotEquals(
        sqs.sent.get(0).messageDeduplicationId(), sqs.sent.get(1).messageDeduplicationId());
    assertEquals(
        sqs.sent.get(0).messageDeduplicationId(), sqs.sent.get(2).messageDeduplicationId());
    var body = JsonReader.parseObject(sqs.sent.get(1).messageBody());
    var decoded =
        AuditEventJson.read(
            new String(
                Base64.getDecoder().decode((String) body.get("data_base64")),
                StandardCharsets.UTF_8));
    assertEquals(first.id(), decoded.id());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INTACT,
        AuditChainVerifier.verify(List.of(decoded), null, null).integrity());
  }

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
    return AuditEvent.builder()
        .id("e-1")
        .occurredAt(Instant.EPOCH)
        .service("svc")
        .action("order.create")
        .resourceType("order")
        .resourceId("o-1")
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void emptyDestinationIsRejectedBeforePublication() {
    try (var client = new FakeSqs()) {
      assertThrows(IllegalArgumentException.class, () -> new SqsAuditPublisher(client, " "));
    }
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
    var body = JsonReader.parseObject(sqs.sent.get(0).messageBody());
    assertEquals("1.0", body.get("specversion"));
    assertEquals("e-1", body.get("id"));
    assertEquals("pe.axiz.audit.event.v1", body.get("type"));
    assertEquals("application/json", body.get("datacontenttype"));
    assertEquals(
        "order",
        AuditEventJson.read(
                new String(
                    Base64.getDecoder().decode((String) body.get("data_base64")),
                    StandardCharsets.UTF_8))
            .resourceType());
  }
}
