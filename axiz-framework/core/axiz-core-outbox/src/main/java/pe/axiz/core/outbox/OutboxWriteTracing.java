package pe.axiz.core.outbox;

import java.util.function.Consumer;
import pe.axiz.core.messaging.EventEnvelope;

/**
 * Conserva la traza de quien llama con cada evento escrito en el outbox. El relay publica el evento
 * más tarde, fuera de la petición: con el contexto de traza almacenado en el evento ({@link
 * EventEnvelope#OUTBOX_TRACEPARENT}), la publicación y sus consumidores continúan la traza de la
 * petición en lugar de iniciar una propia.
 */
@FunctionalInterface
public interface OutboxWriteTracing {

  /** Sin tracing: el evento se almacena tal cual. */
  OutboxWriteTracing NONE = (destination, event, write) -> write.accept(event);

  /**
   * Almacena el evento mediante {@code write}, después de agregar el contexto de traza de quien
   * llama.
   *
   * @param destination destino lógico
   * @param event evento a almacenar
   * @param write almacena el evento (posiblemente marcado)
   */
  void traced(String destination, EventEnvelope event, Consumer<EventEnvelope> write);
}
