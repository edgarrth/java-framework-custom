package pe.axiz.core.messaging;

/** Lo que hace un consumidor con un mensaje después de procesarlo. */
public enum DeliveryDecision {
  /** Procesado (o ya procesado por una entrega previa): se confirma. */
  ACK,
  /** Fallo transitorio: se reentrega tras el backoff. */
  RETRY,
  /**
   * Otra entrega tiene el mensaje (lease válido del inbox): se reentrega cuando ese lease pueda
   * haber terminado, sin contarlo como intento fallido. Nunca se confirma, de modo que una entrega
   * que murió con el lease no pierde el mensaje.
   */
  DEFER,
  /** Fallo permanente o intentos agotados: se envía al destino de dead-letter. */
  DEAD_LETTER
}
