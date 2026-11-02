package pe.axiz.core.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TypedEventHandlerTest {

  record PaymentCreated(String id) {}

  @HandlesEvent("pe.axiz.payments.payment-created.v1")
  static class Handler extends TypedEventHandler<PaymentCreated> {
    @Override
    protected void on(PaymentCreated payload, EventEnvelope envelope) {}
  }

  static class Missing extends TypedEventHandler<String> {
    @Override
    protected void on(String payload, EventEnvelope envelope) {}
  }

  @Test
  void typesComeFromAnnotationAndGenerics() {
    Handler h = new Handler();
    assertEquals("pe.axiz.payments.payment-created.v1", h.eventType());
    assertEquals(PaymentCreated.class, h.payloadType());
    assertThrows(IllegalStateException.class, Missing::new);
    assertEquals(
        "ledger", new EventRoutes("events", Map.of("x.v1", "ledger")).destinationOf("x.v1"));
    assertEquals("events", new EventRoutes("events", Map.of()).destinationOf("y.v1"));
  }
}
