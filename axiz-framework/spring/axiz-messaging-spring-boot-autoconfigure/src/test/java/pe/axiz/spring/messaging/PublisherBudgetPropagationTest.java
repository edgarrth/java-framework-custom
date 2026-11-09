package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.BoundedPublisher;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishBound;
import pe.axiz.core.messaging.RoutingEventPublisher;

class PublisherBudgetPropagationTest {
  @Test
  void observationAndRoutingKeepKnownBoundsAndUnknownRoutes() {
    EventPublisher known =
        mock(EventPublisher.class, withSettings().extraInterfaces(BoundedPublisher.class));
    when(((BoundedPublisher) known).publishBound())
        .thenReturn(PublishBound.of(Duration.ofSeconds(120)));
    EventPublisher unknown = mock(EventPublisher.class);
    var routing = new RoutingEventPublisher(List.of(known, unknown));
    var observed =
        new ObservedEventPublisher(
            routing, ObservationRegistry.NOOP, mock(AxizMessagingProperties.class));
    assertEquals(routing.publishBound(), BoundedPublisher.of(observed));
    assertEquals(
        PublishBound.of(Duration.ofSeconds(120)).merge(PublishBound.unbounded()),
        observed.publishBound());
  }
}
