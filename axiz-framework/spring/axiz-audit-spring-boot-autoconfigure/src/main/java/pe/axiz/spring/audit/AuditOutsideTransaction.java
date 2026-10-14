package pe.axiz.spring.audit;

/** What durable audit does with a successful action recorded outside a transaction. */
public enum AuditOutsideTransaction {
  /** Publish it through the asynchronous publisher (not durable). */
  ASYNC,
  /** Refuse it: every audited success must be part of a business transaction. */
  FAIL
}
