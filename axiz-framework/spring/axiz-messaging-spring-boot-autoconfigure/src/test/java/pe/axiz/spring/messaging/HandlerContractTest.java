package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.core.messaging.TypedEventHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Edge cases of the handler base classes: type resolution, delegation and awaiting. */
class HandlerContractTest {

  static final EventEnvelope ENVELOPE =
      EventEnvelope.builder()
          .id("e-1")
          .source("urn:test")
          .type("pe.axiz.test.thing-happened.v1")
          .time(Instant.EPOCH)
          .subject(null)
          .dataContentType("application/json")
          .data(new byte[0])
          .extensions(Map.of())
          .build();

  @HandlesEvent("pe.axiz.test.thing-happened.v1")
  static class Recording extends TypedEventHandler<String> {
    final AtomicReference<String> seen = new AtomicReference<>();

    @Override
    protected void on(String payload, EventEnvelope envelope) {
      seen.set(payload + "@" + envelope.id());
    }
  }

  /** A subclass of a concrete handler: the payload comes from the class that binds it. */
  @HandlesEvent("pe.axiz.test.thing-happened.v2")
  static class Derived extends Recording {}

  @HandlesEvent("pe.axiz.test.batch-received.v1")
  static class Batch extends TypedEventHandler<List<String>> {
    @Override
    protected void on(List<String> payload, EventEnvelope envelope) {}
  }

  abstract static class Generic<X> extends TypedEventHandler<X> {
    @Override
    protected void on(X payload, EventEnvelope envelope) {}
  }

  /** The payload is a type variable at the level of the base class: it cannot be resolved. */
  @HandlesEvent("pe.axiz.test.generic.v1")
  static class Unresolvable extends Generic<String> {}

  @HandlesEvent("pe.axiz.test.batch-received.v1")
  static class ReactiveBatch extends ReactiveTypedEventHandler<List<String>> {
    @Override
    protected Publisher<Void> on(List<String> payload, EventEnvelope envelope) {
      return Mono.empty();
    }
  }

  @Test
  void handleDelegatesToTheSubclass() throws Exception {
    Recording r = new Recording();
    r.handle("payload", ENVELOPE);
    assertEquals("payload@e-1", r.seen.get());
  }

  @Test
  void typesAreResolvedThroughSubclassesAndParameterizedPayloads() {
    Derived d = new Derived();
    assertEquals("pe.axiz.test.thing-happened.v2", d.eventType());
    assertEquals(String.class, d.payloadType());
    assertEquals(List.class, new Batch().payloadType());
    assertEquals(List.class, new ReactiveBatch().payloadType());
    IllegalStateException e = assertThrows(IllegalStateException.class, Unresolvable::new);
    assertTrue(e.getMessage().contains("payload type"));
  }

  @Test
  void reactPublishesWithoutRunningAndUsesTheDefaultTimeout() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    ReactiveTypedEventHandler<String> h = new Lazy(ran);
    Publisher<Void> p = h.react("x", ENVELOPE);
    assertFalse(ran.get(), "nothing runs before subscription");
    ReactiveTypedEventHandler.await(p, Duration.ofSeconds(1));
    assertTrue(ran.get());
    assertEquals(ReactiveTypedEventHandler.DEFAULT_TIMEOUT, h.timeout());
  }

  @HandlesEvent("pe.axiz.test.thing-happened.v1")
  static class Lazy extends ReactiveTypedEventHandler<String> {
    private final AtomicBoolean ran;

    Lazy(AtomicBoolean ran) {
      this.ran = ran;
    }

    @Override
    protected Publisher<Void> on(String payload, EventEnvelope envelope) {
      return Mono.fromRunnable(() -> ran.set(true));
    }
  }

  @Test
  void valuesAreIgnoredAndCompletionIsTheSignal() throws Exception {
    ReactiveTypedEventHandler.await(Flux.just(1, 2, 3), Duration.ofSeconds(1));
  }

  @Test
  void errorsAreRethrownAsThemselves() {
    AssertionError error = new AssertionError("broken invariant");
    assertSame(
        error,
        assertThrows(
            AssertionError.class,
            () -> ReactiveTypedEventHandler.await(Mono.error(error), Duration.ofSeconds(1))));
    // a Throwable that is neither an Exception nor an Error keeps its wrapper
    Throwable odd = new Throwable("odd");
    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () -> ReactiveTypedEventHandler.await(Mono.error(odd), Duration.ofSeconds(1)));
    assertSame(odd, e.getCause());
  }

  @Test
  void aPublisherThatNeverSubscribesTimesOut() {
    Publisher<Void> silent = subscriber -> {};
    TimeoutException e =
        assertThrows(
            TimeoutException.class,
            () -> ReactiveTypedEventHandler.await(silent, Duration.ofMillis(50)));
    assertTrue(e.getMessage().contains("did not complete"));
  }

  @Test
  void anInterruptedConsumerCancelsAndKeepsTheInterruptFlag() {
    AtomicBoolean cancelled = new AtomicBoolean();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedException.class,
          () ->
              ReactiveTypedEventHandler.await(
                  Mono.never().doOnCancel(() -> cancelled.set(true)), Duration.ofSeconds(5)));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(cancelled.get());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void anInterruptBeforeAnySubscriptionStillPropagates() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedException.class,
          () -> ReactiveTypedEventHandler.await(subscriber -> {}, Duration.ofSeconds(5)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
