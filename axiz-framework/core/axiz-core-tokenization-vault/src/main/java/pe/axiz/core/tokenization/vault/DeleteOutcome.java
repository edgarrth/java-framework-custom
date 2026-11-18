package pe.axiz.core.tokenization.vault;

/** Confirmed results of a revision-and-reference-checked deletion. */
public enum DeleteOutcome {
  DELETED,
  NOT_FOUND,
  CHANGED,
  INTEGRITY_FAILURE
}
