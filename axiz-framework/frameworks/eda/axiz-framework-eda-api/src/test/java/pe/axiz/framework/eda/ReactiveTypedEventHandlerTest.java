package pe.axiz.framework.eda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import pe.axiz.core.messaging.EventEnvelope;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ReactiveTypedEventHandlerTest {

  record PaymentCreated(String id) {}

  static final EventEnvelope ENVELOPE =
      new EventEnvelope(
          "e-1",
          "urn:test",
          "pe.axiz.payments.payment-created.v1",
          Instant.EPOCH,
          null,
          "application/json",
          new byte[0],
          Map.of());

  @HandlesEvent("pe.axiz.payments.payment-created.v1")
  static class Projection extends ReactiveTypedEventHandler<PaymentCreated> {
    final AtomicReference<String> projected = new AtomicReference<>();
    final AtomicReference<String> thread = new AtomicReference<>();

    @Override
    protected Publisher<Void> on(PaymentCreated payload, EventEnvelope envelope) {
      return Mono.fromRunnable(
              () -> {
                projected.set(payload.id());
                thread.set(Thread.currentThread().getName());
              })
          .subscribeOn(Schedulers.parallel())
          .then();
    }
  }

  @HandlesEvent("pe.axiz.payments.payment-created.v1")
  static class Failing extends ReactiveTypedEventHandler<PaymentCreated> {
    @Override
    protected Publisher<Void> on(PaymentCreated payload, EventEnvelope envelope) {
      return Mono.error(new IOException("downstream unavailable"));
    }
  }

  @HandlesEvent("pe.axiz.payments.payment-created.v1")
  static class Slow extends ReactiveTypedEventHandler<PaymentCreated> {
    final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    protected Duration timeout() {
      return Duration.ofMillis(100);
    }

    @Override
    protected Publisher<Void> on(PaymentCreated payload, EventEnvelope envelope) {
      return Mono.<Void>never().doOnCancel(() -> cancelled.set(true));
    }
  }

  @Test
  void theConsumerThreadWaitsForTheNonBlockingHandling() throws Exception {
    Projection p = new Projection();
    assertEquals("pe.axiz.payments.payment-created.v1", p.eventType());
    assertEquals(PaymentCreated.class, p.payloadType());
    p.handle(new PaymentCreated("p-1"), ENVELOPE);
    assertEquals("p-1", p.projected.get());
    assertTrue(p.thread.get().startsWith("parallel"));
  }

  @Test
  void failuresReachTheDispatcherUnwrapped() {
    IOException e =
        assertThrows(
            IOException.class, () -> new Failing().handle(new PaymentCreated("p"), ENVELOPE));
    assertEquals("downstream unavailable", e.getMessage());
  }

  @Test
  void slowHandlingTimesOutAndIsCancelled() {
    Slow slow = new Slow();
    assertThrows(TimeoutException.class, () -> slow.handle(new PaymentCreated("p"), ENVELOPE));
    assertTrue(slow.cancelled.get());
  }
}
