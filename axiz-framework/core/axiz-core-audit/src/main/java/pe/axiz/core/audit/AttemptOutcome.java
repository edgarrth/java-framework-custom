package pe.axiz.core.audit;

/** What is actually known about an earlier durable attempt. A timeout is UNKNOWN. */
public enum AttemptOutcome {
  ROLLED_BACK,
  UNKNOWN
}
