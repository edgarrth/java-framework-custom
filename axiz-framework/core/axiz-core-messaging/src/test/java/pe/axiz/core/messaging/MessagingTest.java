package pe.axiz.core.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.id.IdGenerator;

class MessagingTest {

  @Test
  void wrappedUncertainOrPermanentFailuresDoNotBecomeConsumerRetries() {
    var policy = ConsumerRetryPolicy.defaults();
    var uncertain =
        new CompletionException(new BusinessException(CoreErrors.OUTCOME_UNKNOWN, "synthetic"));
    assertEquals(DeliveryDecision.DEAD_LETTER, policy.onFailure(uncertain, 1));
    assertEquals(
        DeliveryDecision.DEAD_LETTER,
        policy.onFailure(
            new CompletionException(
                new PayloadException("synthetic", new IllegalArgumentException())),
            1));
    assertEquals(
        DeliveryDecision.RETRY,
        policy.onFailure(
            new CompletionException(new BusinessException(CoreErrors.UNAVAILABLE, "synthetic")),
            1));
    var first = new RuntimeException();
    var second = new RuntimeException();
    first.initCause(second);
    second.initCause(first);
    assertEquals(DeliveryDecision.DEAD_LETTER, policy.onFailure(first, 1));
  }

  /** Test codec: payloads are strings. */
  static final class StringCodec implements PayloadCodec {
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
      if (type != String.class) {
        throw new IllegalArgumentException("unsupported");
      }
      String s = new String(data, StandardCharsets.UTF_8);
      if (s.equals("garbage")) {
        throw new IllegalArgumentException("cannot decode");
      }
      return type.cast(s);
    }
  }

  static final class Recorder implements EventHandler<String> {
    final List<String> handled = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public String eventType() {
      return "test.thing-happened.v1";
    }

    @Override
    public Class<String> payloadType() {
      return String.class;
    }

    @Override
    public void handle(String payload, EventEnvelope envelope) {
      if (failWith != null) {
        throw failWith;
      }
      handled.add(payload);
    }
  }

  static final class MemoryDedup implements Deduplicator {
    final Set<String> done = new HashSet<>();
    final Map<String, String> inProgress = new HashMap<>();
    RuntimeException unavailable;

    @Override
    public Claim claim(String consumer, String eventId) {
      if (unavailable != null) {
        throw unavailable;
      }
      if (done.contains(eventId)) {
        return Claim.alreadyProcessed();
      }
      if (inProgress.containsKey(eventId)) {
        return Claim.busy(Duration.ofSeconds(30));
      }
      String token = Claim.newToken();
      inProgress.put(eventId, token);
      return Claim.acquired(token);
    }

    @Override
    public boolean completed(String consumer, String eventId, String token) {
      if (!token.equals(inProgress.get(eventId))) {
        return false;
      }
      inProgress.remove(eventId);
      done.add(eventId);
      return true;
    }

    @Override
    public boolean failed(String consumer, String eventId, String token) {
      return inProgress.remove(eventId, token);
    }
  }

  private final EventFactory factory =
      new EventFactory(
          "urn:axiz:test",
          "test",
          new StringCodec(),
          IdGenerator.uuidV7(),
          Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

  private EventEnvelope event(String payload) {
    RequestMetadata md =
        RequestMetadata.builder("corr-12345678", "req-1")
            .tenantId("t1")
            .channel("web")
            .locale(Locale.ROOT)
            .build();
    return factory.create(null, "thing-happened.v1", "agg-1", payload, md);
  }

  @Test
  void factoryBuildsCloudEvent() {
    EventEnvelope e = event("hello");
    assertEquals("test.thing-happened.v1", e.type());
    assertEquals("urn:axiz:test", e.source());
    assertEquals("agg-1", e.subject());
    assertEquals("corr-12345678", e.correlationId().orElseThrow());
    assertEquals("t1", e.extension("TENANT").orElseThrow());
    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), e.data());
    assertEquals("x", e.withExtension("Foo", "x").extension("foo").orElseThrow());
    assertThrows(IllegalArgumentException.class, () -> e.withExtension("bad-name", "x"));
    assertTrue(e.toString().contains("test.thing-happened.v1"));
    EventEnvelope noMetadata = factory.create("fixed-id", "x", null, "p", null);
    assertEquals("fixed-id", noMetadata.id());
    assertTrue(noMetadata.extensions().isEmpty());
  }

  @Test
  void headersRoundTrip() {
    EventEnvelope e = event("p");
    Map<String, String> headers = CloudEventHeaders.toHeaders(e);
    EventEnvelope back = CloudEventHeaders.fromHeaders(headers::get, headers.keySet(), e.data());
    assertEquals(e, back);
    assertEquals(e.type(), back.type());
    assertEquals(e.time(), back.time());
    assertEquals("t1", back.extension("tenant").orElseThrow());
    assertThrows(
        PayloadException.class,
        () -> CloudEventHeaders.fromHeaders(Map.<String, String>of()::get, List.of(), new byte[0]));
    Map<String, String> badTime = new HashMap<>(headers);
    badTime.put("ce_time", "yesterday");
    assertThrows(
        PayloadException.class,
        () -> CloudEventHeaders.fromHeaders(badTime::get, badTime.keySet(), new byte[0]));
  }

  @Test
  void dispatcherProcessesDeduplicatesAndClassifiesFailures() {
    Recorder handler = new Recorder();
    MemoryDedup dedup = new MemoryDedup();
    EventDispatcher dispatcher =
        new EventDispatcher(
            "notifications",
            List.of(handler),
            new StringCodec(),
            dedup,
            ConsumerRetryPolicy.defaults(),
            false);
    EventEnvelope e = event("one");
    assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(e, 1));
    assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(e, 2));
    assertEquals(List.of("one"), handler.handled);

    handler.failWith = new RuntimeException(new SocketTimeoutException());
    EventEnvelope transientFail = event("two");
    assertEquals(DeliveryDecision.RETRY, dispatcher.dispatch(transientFail, 1));
    assertEquals(DeliveryDecision.DEAD_LETTER, dispatcher.dispatch(transientFail, 5));

    handler.failWith = new BusinessException(CoreErrors.CONFLICT, "rule");
    assertEquals(DeliveryDecision.DEAD_LETTER, dispatcher.dispatch(event("three"), 1));

    handler.failWith = null;
    assertEquals(DeliveryDecision.DEAD_LETTER, dispatcher.dispatch(event("garbage"), 1));
    EventEnvelope retried = event("four");
    handler.failWith = new IllegalStateException("db down");
    assertEquals(DeliveryDecision.RETRY, dispatcher.dispatch(retried, 1));
    handler.failWith = null;
    assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(retried, 2));
    assertTrue(handler.handled.contains("four"));
  }

  @Test
  void aMessageHeldByAnotherDeliveryIsDeferredNeverAcknowledged() {
    Recorder handler = new Recorder();
    MemoryDedup dedup = new MemoryDedup();
    EventDispatcher dispatcher =
        new EventDispatcher(
            "notifications",
            List.of(handler),
            new StringCodec(),
            dedup,
            ConsumerRetryPolicy.defaults(),
            false);
    EventEnvelope e = event("held");
    // delivery A claimed the message and died before finishing
    dedup.inProgress.put(MessageKeys.of(e), "token-of-a");
    EventDispatcher.Outcome outcome = dispatcher.dispatchWithDelay(e, 1);
    assertEquals(DeliveryDecision.DEFER, outcome.decision());
    assertEquals(Duration.ofSeconds(30), outcome.retryAfter());
    assertTrue(handler.handled.isEmpty(), "the handler did not run");
    // once A's lease is over the redelivery processes it
    dedup.inProgress.clear();
    assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(e, 2));
    assertEquals(List.of("held"), handler.handled);
  }

  @Test
  void anUnavailableInboxIsATransientFailure() {
    Recorder handler = new Recorder();
    MemoryDedup dedup = new MemoryDedup();
    dedup.unavailable = new IllegalStateException("inbox database down");
    EventDispatcher dispatcher =
        new EventDispatcher(
            "notifications",
            List.of(handler),
            new StringCodec(),
            dedup,
            ConsumerRetryPolicy.defaults(),
            false);
    assertEquals(DeliveryDecision.RETRY, dispatcher.dispatch(event("one"), 1));
    assertTrue(handler.handled.isEmpty());
  }

  /**
   * Regression: two producers may use the same id; only source + id identifies an event, so both
   * are processed, while a redelivery of the same one is not.
   */
  @Test
  void deduplicationUsesSourceAndId() {
    Recorder handler = new Recorder();
    MemoryDedup dedup = new MemoryDedup();
    EventDispatcher dispatcher =
        new EventDispatcher(
            "notifications",
            List.of(handler),
            new StringCodec(),
            dedup,
            ConsumerRetryPolicy.defaults(),
            false);
    EventEnvelope base = event("from-a");
    EventEnvelope fromA =
        EventEnvelope.builder()
            .id("same-id")
            .source("urn:source:A")
            .type(base.type())
            .time(base.time())
            .subject(base.subject())
            .dataContentType(base.dataContentType())
            .data("from-a".getBytes(StandardCharsets.UTF_8))
            .extensions(base.extensions())
            .build();
    EventEnvelope fromB =
        EventEnvelope.builder()
            .id("same-id")
            .source("urn:source:B")
            .type(base.type())
            .time(base.time())
            .subject(base.subject())
            .dataContentType(base.dataContentType())
            .data("from-b".getBytes(StandardCharsets.UTF_8))
            .extensions(base.extensions())
            .build();
    assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(fromA, 1));
    assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(fromB, 1));
    assertEquals(DeliveryDecision.ACK, dispatcher.dispatch(fromA, 1));
    assertEquals(List.of("from-a", "from-b"), handler.handled);
  }

  @Test
  void messageKeysAreUnambiguousAndBounded() {
    assertEquals("12:urn:source:A|e-1", MessageKeys.of("urn:source:A", "e-1"));
    assertTrue(
        !MessageKeys.of("a|b", "c").equals(MessageKeys.of("a", "b|c")),
        "a separator inside a source never collides");
    String longKey = MessageKeys.of("urn:" + "x".repeat(200), "e-1");
    assertTrue(longKey.startsWith("sha256:") && longKey.length() <= MessageKeys.MAX_LENGTH);
    assertEquals(longKey, MessageKeys.of("urn:" + "x".repeat(200), "e-1"));
  }

  @Test
  void aLateCompletionCannotOverwriteTheNewOwner() {
    MemoryDedup dedup = new MemoryDedup();
    Deduplicator.Claim a = dedup.claim("c", "e-1");
    dedup.inProgress.put("e-1", "token-of-b"); // A's lease expired and B took it over
    assertEquals(false, dedup.completed("c", "e-1", a.token()));
    assertEquals(false, dedup.failed("c", "e-1", a.token()));
    assertEquals("token-of-b", dedup.inProgress.get("e-1"));
  }

  @Test
  void unknownTypesAndSubscriptions() {
    Recorder handler = new Recorder();
    EventEnvelope other = factory.create(null, "other.v1", null, "x", null);
    assertEquals(
        DeliveryDecision.DEAD_LETTER,
        new EventDispatcher(
                "c",
                List.of(handler),
                new StringCodec(),
                Deduplicator.none(),
                ConsumerRetryPolicy.defaults(),
                false)
            .dispatch(other, 1));
    EventDispatcher lenient =
        new EventDispatcher(
            "c",
            List.of(handler),
            new StringCodec(),
            Deduplicator.none(),
            ConsumerRetryPolicy.defaults(),
            true);
    assertEquals(DeliveryDecision.ACK, lenient.dispatch(other, 1));
    lenient.verifySubscriptions(List.of("test.thing-happened.v1"));
    assertThrows(
        IllegalStateException.class, () -> lenient.verifySubscriptions(List.of("missing.v1")));
    assertThrows(
        IllegalStateException.class,
        () ->
            new EventDispatcher(
                "c",
                List.of(handler, new Recorder()),
                new StringCodec(),
                Deduplicator.none(),
                ConsumerRetryPolicy.defaults(),
                true));
  }

  @Test
  void routingPublisher() {
    List<String> sent = new ArrayList<>();
    EventPublisher kafka =
        new EventPublisher() {
          @Override
          public PublishResult publish(String destination, EventEnvelope event) {
            sent.add("kafka:" + destination);
            return new PublishResult(event.id(), destination, "0");
          }

          @Override
          public boolean supports(String destination) {
            return !destination.startsWith("https://sqs");
          }
        };
    EventPublisher sqs =
        (destination, event) -> {
          sent.add("sqs:" + destination);
          return new PublishResult(event.id(), destination, null);
        };
    RoutingEventPublisher router = new RoutingEventPublisher(List.of(kafka, sqs));
    router.publish("payments.events", event("a"));
    router.publish("https://sqs.local/q", event("b"));
    assertEquals(List.of("kafka:payments.events", "sqs:https://sqs.local/q"), sent);
    assertTrue(router.supports("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RoutingEventPublisher(List.of()).publish("x", event("c")));
  }
}
