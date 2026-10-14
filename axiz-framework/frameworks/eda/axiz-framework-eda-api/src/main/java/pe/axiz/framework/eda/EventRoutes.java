package pe.axiz.framework.eda;

import java.util.Map;
import java.util.Objects;

/**
 * Choreography routes: the logical destination of each event type a service emits in reaction to
 * the events it consumes. Unknown types go to the default destination.
 *
 * @param defaultDestination destination of unrouted types
 * @param routes destination by event type
 */
public record EventRoutes(String defaultDestination, Map<String, String> routes) {

  public EventRoutes {
    Objects.requireNonNull(defaultDestination);
    routes = Map.copyOf(routes);
  }

  public String destinationOf(String eventType) {
    return routes.getOrDefault(eventType, defaultDestination);
  }
}
