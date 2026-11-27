package pe.axiz.core.messaging;

import java.util.Map;
import java.util.Objects;

/**
 * Rutas de coreografía: el destino lógico de cada tipo de evento que un servicio emite en reacción
 * a los eventos que consume. Los tipos desconocidos van al destino por defecto.
 *
 * @param defaultDestination destino de los tipos sin ruta
 * @param routes destino por tipo de evento
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
