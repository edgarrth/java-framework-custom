package pe.axiz.core.audit;

/** Read-only live measurements of an audit queue; no events or lifecycle controls are exposed. */
public interface AuditQueueMetrics {
  int pending();

  long deliveredCount();

  long fallbackCount();

  long failureCount();

  long lostCount();

  long unconfirmedCount();

  boolean workerAlive();
}
