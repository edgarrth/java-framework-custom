package pe.axiz.adapter.aws.transport;

/**
 * Estadísticas de admisión de finalización de solo lectura, sin acceso al ciclo de vida del
 * executor.
 */
public interface CompletionPool {
  int getActiveCount();

  int getQueueSize();
}
