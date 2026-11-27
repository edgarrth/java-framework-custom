package pe.axiz.spring.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.ConsumerRetryPolicy;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.spring.messaging.ObservedDispatcher;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class SqsEventConsumerTest {

  /** Registra lo que el consumidor pide hacer a SQS. */
  static final class FakeSqs implements SqsClient {
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<SendMessageRequest> sent = new CopyOnWriteArrayList<>();

    @Override
    public ChangeMessageVisibilityResponse changeMessageVisibility(
        Consumer<ChangeMessageVisibilityRequest.Builder> b) {
      ChangeMessageVisibilityRequest.Builder r = ChangeMessageVisibilityRequest.builder();
      b.accept(r);
      calls.add("visibility:" + r.build().visibilityTimeout());
      return ChangeMessageVisibilityResponse.builder().build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(Consumer<DeleteMessageRequest.Builder> b) {
      calls.add("delete");
      return DeleteMessageResponse.builder().build();
    }

    @Override
    public SendMessageResponse sendMessage(Consumer<SendMessageRequest.Builder> b) {
      var request = SendMessageRequest.builder();
      b.accept(request);
      sent.add(request.build());
      calls.add("dlq");
      return SendMessageResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "sqs";
    }

    @Override
    public void close() {}
  }

  static final PayloadCodec CODEC =
      new PayloadCodec() {
        @Override
        public String contentType() {
          return "text/plain";
        }

        @Override
        public byte[] encode(Object payload) {
          return payload.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T decode(byte[] data, Class<T> type) {
          return type.cast(new String(data, StandardCharsets.UTF_8));
        }
      };

  static final class Handler implements EventHandler<String> {
    final List<String> seen = new CopyOnWriteArrayList<>();

    @Override
    public String eventType() {
      return "pe.axiz.test.thing.v1";
    }

    @Override
    public Class<String> payloadType() {
      return String.class;
    }

    @Override
    public void handle(String payload, EventEnvelope envelope) {
      seen.add(payload);
    }
  }

  private static Message message(String body) {
    return Message.builder().messageId("m-1").receiptHandle("r-1").body(body).build();
  }

  static String validBody() {
    return CloudEventJson.write(
        EventEnvelope.builder()
            .id("e-1")
            .source("urn:test")
            .type("pe.axiz.test.thing.v1")
            .time(Instant.EPOCH)
            .subject(null)
            .dataContentType("text/plain")
            .data("hello".getBytes(StandardCharsets.UTF_8))
            .extensions(Map.of())
            .build());
  }

  private static SqsEventConsumer consumer(FakeSqs sqs, Deduplicator dedup, Handler handler) {
    EventDispatcher dispatcher =
        new EventDispatcher(
            "c", List.of(handler), CODEC, dedup, ConsumerRetryPolicy.defaults(), false);
    return new SqsEventConsumer(
        sqs,
        new ObservedDispatcher(dispatcher, ObservationRegistry.NOOP, Clock.systemUTC()),
        new AxizMessagingSqsProperties(
            List.of("https://sqs/q"),
            Map.of("https://sqs/q", "https://sqs/q-dlq"),
            Duration.ofSeconds(20),
            10,
            Duration.ofSeconds(60)),
        Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(5)));
  }

  @Test
  void fifoDeadLettersKeepTheirGroupAndRetryWithAStableDeduplicationId() {
    var sqs = new FakeSqs();
    var dispatcher =
        new EventDispatcher(
            "c",
            List.of(new Handler()),
            CODEC,
            Deduplicator.none(),
            ConsumerRetryPolicy.defaults(),
            false);
    var consumer =
        new SqsEventConsumer(
            sqs,
            new ObservedDispatcher(dispatcher, ObservationRegistry.NOOP, Clock.systemUTC()),
            new AxizMessagingSqsProperties(
                List.of("https://sqs/q.fifo"),
                Map.of("https://sqs/q.fifo", "https://sqs/dlq.fifo"),
                Duration.ofSeconds(1),
                1,
                Duration.ofSeconds(5)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    var poison =
        message("not-json").toBuilder()
            .attributes(Map.of(MessageSystemAttributeName.MESSAGE_GROUP_ID, "customer-1"))
            .build();
    consumer.process("https://sqs/q.fifo", poison);
    consumer.process("https://sqs/q.fifo", poison);
    for (var request : sqs.sent) {
      assertEquals("customer-1", request.messageGroupId());
      assertEquals("m-1", request.messageDeduplicationId());
      assertEquals("not-json", request.messageBody());
    }
    assertEquals(List.of("dlq", "delete", "dlq", "delete"), sqs.calls);
    consumer.process("https://sqs/q.fifo", message("not-json"));
    assertEquals("axiz-dead-letter", sqs.sent.getLast().messageGroupId());
  }

  @Test
  void onlyUnreadableMessagesGoStraightToTheDeadLetterQueue() {
    FakeSqs sqs = new FakeSqs();
    Handler handler = new Handler();
    consumer(sqs, Deduplicator.none(), handler).process("https://sqs/q", message("{not json"));
    assertEquals(List.of("dlq", "delete"), sqs.calls);
  }

  @Test
  void anUnavailableInboxIsRetriedNotDeadLettered() {
    FakeSqs sqs = new FakeSqs();
    Handler handler = new Handler();
    Deduplicator down =
        new Deduplicator() {
          @Override
          public Claim claim(String consumer, String eventId) {
            throw new IllegalStateException("inbox database down");
          }

          @Override
          public boolean completed(String consumer, String eventId, String token) {
            return true;
          }

          @Override
          public boolean failed(String consumer, String eventId, String token) {
            return true;
          }
        };
    consumer(sqs, down, handler).process("https://sqs/q", message(validBody()));
    assertEquals(1, sqs.calls.size());
    assertTrue(sqs.calls.get(0).startsWith("visibility:"), sqs.calls.toString());
    assertTrue(handler.seen.isEmpty());
  }

  @Test
  void aMessageHeldByAnotherDeliveryWaitsForItsLease() {
    FakeSqs sqs = new FakeSqs();
    Deduplicator busy =
        new Deduplicator() {
          @Override
          public Claim claim(String consumer, String eventId) {
            return Claim.busy(Duration.ofSeconds(90));
          }

          @Override
          public boolean completed(String consumer, String eventId, String token) {
            return true;
          }

          @Override
          public boolean failed(String consumer, String eventId, String token) {
            return true;
          }
        };
    consumer(sqs, busy, new Handler()).process("https://sqs/q", message(validBody()));
    assertEquals(List.of("visibility:91"), sqs.calls, "never deleted while another holds it");
  }

  @Test
  void dispatcherFailureRetriesAndPermanentDecisionsHonorBrokerRedriveOrExplicitDlq() {
    var sqs = new FakeSqs();
    var dispatcher = mock(ObservedDispatcher.class);
    when(dispatcher.dispatch(anyString(), any(EventEnvelope.class), anyInt()))
        .thenThrow(new IllegalStateException("dispatch unavailable"))
        .thenReturn(
            new EventDispatcher.Outcome(
                DeliveryDecision.DEAD_LETTER,
                Duration.ZERO,
                "java.lang.IllegalStateException at pe.axiz.Handler.handle(Handler.java:1)"));
    var consumer =
        new SqsEventConsumer(
            sqs,
            dispatcher,
            new AxizMessagingSqsProperties(
                List.of(),
                Map.of("https://sqs/q", "https://sqs/dlq"),
                Duration.ofSeconds(20),
                10,
                Duration.ofSeconds(60)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1)));
    consumer.stop(); // Un contexto puede fallar antes de arrancar; detenerlo debe ser seguro.
    consumer.process("https://sqs/q", message(validBody()));
    assertEquals(List.of("visibility:1"), sqs.calls);
    sqs.calls.clear();
    consumer.process("https://sqs/q", message(validBody()));
    assertEquals(List.of("dlq", "delete"), sqs.calls);
    assertEquals(
        "java.lang.IllegalStateException at pe.axiz.Handler.handle(Handler.java:1)",
        sqs.sent
            .getLast()
            .messageAttributes()
            .get(SqsEventConsumer.FAILURE_ATTRIBUTE)
            .stringValue());
    sqs.calls.clear();
    consumer.process("https://sqs/unmapped", message("invalid"));
    assertEquals(List.of("visibility:0"), sqs.calls);
  }

  @Test
  void processedMessagesAreDeleted() {
    FakeSqs sqs = new FakeSqs();
    Handler handler = new Handler();
    consumer(sqs, Deduplicator.none(), handler).process("https://sqs/q", message(validBody()));
    assertEquals(List.of("delete"), sqs.calls);
    assertEquals(List.of("hello"), handler.seen);
  }
}
