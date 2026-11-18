package pe.axiz.core.tokenization.vault;

/** Confirmed outcomes of revision-fenced maintenance; a missing acknowledgement is an exception. */
public enum MigrationOutcome {
  MIGRATED,
  UNCHANGED,
  CHANGED,
  STALE_KEY_STATE,
  INTEGRITY_FAILURE
}
