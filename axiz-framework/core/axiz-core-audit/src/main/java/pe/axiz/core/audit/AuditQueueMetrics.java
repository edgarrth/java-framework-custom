package pe.axiz.core.audit;

/**
 * Mediciones en vivo de solo lectura de una cola de auditoría; no se exponen eventos ni controles
 * de ciclo de vida.
 */
public interface AuditQueueMetrics {
  int pending();

  long deliveredCount();

  long fallbackCount();

  long failureCount();

  long lostCount();

  long unconfirmedCount();

  boolean workerAlive();
}
