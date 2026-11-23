package pe.axiz.core.messaging;

/**
 * Publica eventos y espera la confirmación del broker. Las implementaciones son sin estado y
 * thread-safe (sin estado compartido entre envíos).
 */
public interface EventPublisher {

  /**
   * Publica el evento.
   *
   * @param destination destino lógico resuelto por el adaptador (tópico, URL de cola, ARN de
   *     tópico)
   * @param event el evento
   * @return la confirmación
   * @throws PublishException cuando el broker no confirma
   */
  PublishResult publish(String destination, EventEnvelope event);

  /** Indica si este publicador maneja el destino (se usa para encaminar entre MSK, SQS y SNS). */
  default boolean supports(String destination) {
    return true;
  }
}
