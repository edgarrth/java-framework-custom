package pe.axiz.core.tokenization.vault;

/**
 * Resultados confirmados de un mantenimiento con fencing por revisión; la falta de acuse es una
 * excepción.
 */
public enum MigrationOutcome {
  MIGRATED,
  UNCHANGED,
  CHANGED,
  STALE_KEY_STATE,
  INTEGRITY_FAILURE
}
