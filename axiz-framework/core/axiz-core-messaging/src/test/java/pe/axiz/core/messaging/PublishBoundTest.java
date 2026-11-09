package pe.axiz.core.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublishBoundTest {
  private record Bounded(PublishBound publishBound) implements EventPublisher, BoundedPublisher {
    @Override
    public PublishResult publish(String destination, EventEnvelope event) {
      return new PublishResult(event.id(), destination, null);
    }
  }

  @Test
  void compositionPreservesEveryKnownBoundAndEveryUnknownRoute() {
    var shortCall = PublishBound.of(Duration.ofSeconds(10));
    var kafka = PublishBound.of(Duration.ofSeconds(120));
    for (var bound : List.of(kafka.merge(shortCall), shortCall.merge(kafka), kafka.merge(kafka))) {
      assertEquals(Duration.ofSeconds(120), bound.budget(Optional.empty()));
      assertEquals(Duration.ofSeconds(120), bound.budget(Optional.of(Duration.ofSeconds(1))));
    }
    for (var bound :
        List.of(kafka.merge(PublishBound.unbounded()), PublishBound.unbounded().merge(kafka))) {
      assertTrue(bound.hasUnbounded());
      assertThrows(IllegalArgumentException.class, () -> bound.budget(Optional.empty()));
      assertEquals(Duration.ofSeconds(120), bound.budget(Optional.of(Duration.ofSeconds(10))));
      assertEquals(Duration.ofSeconds(180), bound.budget(Optional.of(Duration.ofSeconds(180))));
    }
    assertEquals(
        PublishBound.unbounded(), PublishBound.unbounded().merge(PublishBound.unbounded()));
    assertEquals(
        Duration.ofSeconds(1), PublishBound.unbounded().budget(Optional.of(Duration.ofSeconds(1))));
  }

  @Test
  void everyPublisherMustDescribeAValidBudgetOrExplicitlyRemainUnknown() {
    assertThrows(IllegalArgumentException.class, () -> new PublishBound(Optional.empty(), false));
    for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
      assertThrows(IllegalArgumentException.class, () -> PublishBound.of(invalid));
      assertThrows(
          IllegalArgumentException.class,
          () -> PublishBound.unbounded().budget(Optional.of(invalid)));
    }
    EventPublisher custom =
        (destination, event) -> new PublishResult(event.id(), destination, null);
    assertEquals(PublishBound.unbounded(), BoundedPublisher.of(custom));
    assertEquals(PublishBound.unbounded(), new RoutingEventPublisher(List.of()).publishBound());
    assertEquals(
        PublishBound.unbounded(), new RoutingEventPublisher(List.of(custom)).publishBound());
  }

  @Test
  void routingPropagatesTheMaximumAndTheExistenceOfUnknownRoutes() {
    EventPublisher shortCall = new Bounded(PublishBound.of(Duration.ofSeconds(10)));
    EventPublisher kafka = new Bounded(PublishBound.of(Duration.ofSeconds(120)));
    assertEquals(
        PublishBound.of(Duration.ofSeconds(120)),
        new RoutingEventPublisher(List.of(shortCall, kafka)).publishBound());
    EventPublisher unknown =
        (destination, event) -> new PublishResult(event.id(), destination, null);
    var mixed = new RoutingEventPublisher(List.of(shortCall, kafka, unknown)).publishBound();
    assertTrue(mixed.hasUnbounded());
    assertEquals(Duration.ofSeconds(120), mixed.budget(Optional.of(Duration.ofSeconds(1))));
  }
}
