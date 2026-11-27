package pe.axiz.core.tokenization.vault;

/** Resultados confirmados de un borrado verificado por revisión y por referencias. */
public enum DeleteOutcome {
  DELETED,
  NOT_FOUND,
  CHANGED,
  INTEGRITY_FAILURE
}
