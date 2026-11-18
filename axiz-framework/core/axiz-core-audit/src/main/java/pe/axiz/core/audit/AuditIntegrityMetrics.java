package pe.axiz.core.audit;

import java.util.Map;

/** Operational counters only. Observed sequences are never trusted completeness checkpoints. */
public interface AuditIntegrityMetrics {
  long detailsSanitizedCount();

  /** Highest locally assigned sequence by lane, including attempts that may roll back. */
  Map<String, Long> chainSequences();
}
