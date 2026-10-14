package pe.axiz.core.audit;

/** When an audited action is recorded. */
public enum AuditPolicy {
  ALWAYS,
  ON_SUCCESS,
  ON_FAILURE;

  public boolean applies(AuditEvent.Outcome outcome) {
    return this == ALWAYS
        || (this == ON_SUCCESS && outcome == AuditEvent.Outcome.SUCCESS)
        || (this == ON_FAILURE && outcome == AuditEvent.Outcome.FAILURE);
  }
}
