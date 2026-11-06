package pe.axiz.core.commons;

/** Text-only validation of configured SQL identifiers; independent of JDBC and database clients. */
public final class SqlIdentifiers {
  private SqlIdentifiers() {}

  /** Accepts an unquoted PostgreSQL-compatible identifier, with at most 63 ASCII characters. */
  public static String require(String name) {
    if (!name.matches("^[a-z_][a-z0-9_]{0,62}$"))
      throw new IllegalArgumentException("Invalid SQL identifier: " + name);
    return name;
  }
}
