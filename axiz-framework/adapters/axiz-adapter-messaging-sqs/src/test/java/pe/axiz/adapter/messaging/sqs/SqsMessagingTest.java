package pe.axiz.adapter.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.test.support.AsyncPublisherProbe;
import pe.axiz.test.support.ImmediateAsyncFakes;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsMessagingTest {

  static final class FakeSqs implements SqsClient {
    final List<SendMessageRequest> sent = new ArrayList<>();
    boolean fail;

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest r) {
      if (fail) {
        throw SqsException.builder().message("down").build();
      }
      sent.add(r);
      return SendMessageResponse.builder().messageId("m-" + sent.size()).build();
    }

    @Override
    public String serviceName() {
      return "sqs";
    }

    @Override
    public void close() {}
  }

  private static EventEnvelope event() {
    return new EventEnvelope(
        "e-1",
        "urn:s",
        "t.v1",
        Instant.parse("2026-01-01T00:00:00Z"),
        "agg-1",
        "application/json",
        "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  @Test
  void fifoPublishAndRoundTrip() {
    for (boolean nativeAsync : List.of(false, true)) {
      FakeSqs sqs = new FakeSqs();
      EventPublisher p =
          nativeAsync
              ? new AsyncPublisherProbe(
                  new SqsAsyncEventPublisher(ImmediateAsyncFakes.adapt(SqsAsyncClient.class, sqs)))
              : new SqsEventPublisher(sqs);
      String url = "https://sqs.us-east-1.amazonaws.com/123/notifications.fifo";
      assertTrue(p.supports(url));
      assertFalse(p.supports("payments.events"));
      assertEquals("m-1", p.publish(url, event()).brokerMessageId());
      SendMessageRequest r = sqs.sent.get(0);
      assertEquals("agg-1", r.messageGroupId());
      assertEquals("e-1", r.messageDeduplicationId());
      Message m =
          Message.builder()
              .body(r.messageBody())
              .attributesWithStrings(Map.of("ApproximateReceiveCount", "3"))
              .build();
      assertEquals(event(), SqsEnvelopes.from(m));
      assertEquals(3, SqsEnvelopes.attempt(m));
      assertEquals(1, SqsEnvelopes.attempt(Message.builder().body("{}").build()));
      assertTrue(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT != null);
    }
  }

  @Test
  void standardQueueAndFailures() {
    FakeSqs sqs = new FakeSqs();
    new SqsEventPublisher(sqs).publish("https://sqs.local/1/q", event());
    assertNull(sqs.sent.get(0).messageGroupId());
    sqs.fail = true;
    assertThrows(
        PublishException.class,
        () -> new SqsEventPublisher(sqs).publish("https://sqs.local/1/q", event()));
  }
}
