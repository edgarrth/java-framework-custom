package pe.axiz.core.audit;

import java.util.Map;

/**
 * Solo contadores operativos. Las secuencias observadas nunca son checkpoints de completitud
 * confiables.
 */
public interface AuditIntegrityMetrics {
  long detailsSanitizedCount();

  /**
   * Secuencia más alta asignada localmente por carril, incluidos los intentos que pueden hacer
   * rollback.
   */
  Map<String, Long> chainSequences();
}
