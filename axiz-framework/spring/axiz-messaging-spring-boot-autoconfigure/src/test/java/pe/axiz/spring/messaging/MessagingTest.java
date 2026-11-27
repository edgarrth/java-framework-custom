package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;

class MessagingTest {

  public record OrderPlaced(String orderId, String amount) {}

  static final class Recorder implements EventPublisher {
    final List<String> destinations = new CopyOnWriteArrayList<>();
    final List<EventEnvelope> events = new CopyOnWriteArrayList<>();

    @Override
    public PublishResult publish(String destination, EventEnvelope event) {
      destinations.add(destination);
      events.add(event);
      return new PublishResult(event.id(), destination, "m-1");
    }

    @Override
    public boolean supports(String destination) {
      return destination.startsWith("memory:");
    }
  }

  static final class OrderHandler implements EventHandler<OrderPlaced> {
    final List<String> seen = new CopyOnWriteArrayList<>();

    @Override
    public String eventType() {
      return "pe.axiz.orders.order-placed.v1";
    }

    @Override
    public Class<OrderPlaced> payloadType() {
      return OrderPlaced.class;
    }

    @Override
    public void handle(OrderPlaced payload, EventEnvelope envelope) {
      seen.add(payload.orderId() + "/" + RequestContext.get().correlationId());
    }
  }

  @Test
  void publishesCloudEventsAndDispatchesWithContext() {
    Recorder recorder = new Recorder();
    OrderHandler handler = new OrderHandler();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizMessagingAutoConfiguration.class))
        .withPropertyValues(
            "spring.application.name=orders",
            "axiz.messaging.type-prefix=pe.axiz.orders",
            "axiz.messaging.destinations.orders=memory:orders",
            "axiz.messaging.consumer.subscriptions=pe.axiz.orders.order-placed.v1")
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withBean(EventPublisherAdapter.class, () -> new EventPublisherAdapter("memory", recorder))
        .withBean(OrderHandler.class, () -> handler)
        .run(
            ctx -> {
              AxizEvents events = ctx.getBean(AxizEvents.class);
              RequestMetadata m = RequestMetadata.builder("corr-12345678", "req-12345678").build();
              try (RequestContext.Scope ignored = RequestContext.open(m)) {
                events.publish("orders", "order-placed.v1", "o-1", new OrderPlaced("o-1", "10.00"));
              }
              assertEquals(List.of("memory:orders"), recorder.destinations);
              EventEnvelope sent = recorder.events.get(0);
              assertEquals("pe.axiz.orders.order-placed.v1", sent.type());
              assertEquals("urn:axiz:orders", sent.source());
              assertEquals("corr-12345678", sent.correlationId().orElseThrow());
              DeliveryDecision d =
                  ctx.getBean(ObservedDispatcher.class).dispatch("orders", sent, 1).decision();
              assertEquals(DeliveryDecision.ACK, d);
              assertEquals(List.of("o-1/corr-12345678"), handler.seen);
            });
  }

  @Test
  void missingHandlerForSubscriptionFailsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizMessagingAutoConfiguration.class))
        .withPropertyValues("axiz.messaging.consumer.subscriptions=pe.axiz.other.v1")
        .run(ctx -> assertTrue(ctx.getStartupFailure() != null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EventEnvelope.builder()
                .id("1")
                .source("s")
                .type("t")
                .time(Instant.EPOCH)
                .subject(null)
                .dataContentType("application/json")
                .data(new byte[0])
                .extensions(Map.of("Bad-Name", "x"))
                .build());
  }
}
