package pe.axiz.adapter.outbox.jdbc;

/** Diferencias de SQL entre las bases de datos soportadas. */
public enum Dialect {
  /** Aurora PostgreSQL: los relays concurrentes omiten las filas bloqueadas por otros. */
  POSTGRESQL(" FOR UPDATE SKIP LOCKED"),
  /** H2 (pruebas y ejecuciones locales). */
  H2(" FOR UPDATE");

  private final String lockClause;

  Dialect(String lockClause) {
    this.lockClause = lockClause;
  }

  public String nowExpression() {
    return this == POSTGRESQL ? "statement_timestamp()" : "CURRENT_TIMESTAMP";
  }

  String leasedUntilExpression() {
    return this == POSTGRESQL
        ? "statement_timestamp() + make_interval(secs => ?)"
        : "DATEADD(MILLISECOND, ?, CURRENT_TIMESTAMP)";
  }

  public String lockClause() {
    return lockClause;
  }
}
