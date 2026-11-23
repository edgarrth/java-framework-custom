package pe.axiz.payments.process.domain;

/** Estado de un proceso de pago tal como lo muestra la API. */
public enum ProcessStatus {
  /** Los pasos están en ejecución. */
  RUNNING,
  /**
   * El resultado de un débito o de una transferencia es incierto (timeout, respuesta perdida): se
   * está verificando con el participante y mientras tanto no se deshace nada.
   */
  VERIFYING,
  /** Un paso falló y se están deshaciendo los pasos completados. */
  COMPENSATING,
  /** Débito y transferencia realizados; la orden está completada. */
  COMPLETED,
  /** Un paso falló y se deshizo todo; la orden está rechazada. */
  REJECTED,
  /** Falló una acción de deshacer: operaciones debe revisar el pago. */
  NEEDS_ATTENTION
}
