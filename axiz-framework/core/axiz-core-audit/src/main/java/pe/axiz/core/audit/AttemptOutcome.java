package pe.axiz.core.audit;

/** Lo que realmente se sabe de un intento durable previo. Un timeout es UNKNOWN. */
public enum AttemptOutcome {
  ROLLED_BACK,
  UNKNOWN
}
