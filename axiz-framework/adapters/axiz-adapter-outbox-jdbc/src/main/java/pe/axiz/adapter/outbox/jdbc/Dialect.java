package pe.axiz.adapter.outbox.jdbc;

/** SQL differences between the supported databases. */
public enum Dialect {
  /** Aurora PostgreSQL: concurrent relays skip rows locked by others. */
  POSTGRESQL(" FOR UPDATE SKIP LOCKED"),
  /** H2 (tests and local runs). */
  H2(" FOR UPDATE");

  private final String lockClause;

  Dialect(String lockClause) {
    this.lockClause = lockClause;
  }

  public String lockClause() {
    return lockClause;
  }
}
