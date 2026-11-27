package pe.axiz.kit.sql;

/**
 * Validación solo de texto de los identificadores SQL configurados; independiente de JDBC y de los
 * clientes de base de datos.
 */
public final class SqlIdentifiers {
  private SqlIdentifiers() {}

  /**
   * Acepta un identificador compatible con PostgreSQL sin comillas, con como máximo 63 caracteres
   * ASCII.
   */
  public static String require(String name) {
    if (!name.matches("^[a-z_][a-z0-9_]{0,62}$"))
      throw new IllegalArgumentException("Invalid SQL identifier: " + name);
    return name;
  }
}
