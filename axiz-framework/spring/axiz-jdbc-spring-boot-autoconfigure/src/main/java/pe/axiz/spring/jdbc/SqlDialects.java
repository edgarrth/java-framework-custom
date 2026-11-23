package pe.axiz.spring.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Detecta el producto de base de datos para elegir los dialectos SQL (PostgreSQL en clústeres, H2
 * en pruebas).
 */
public final class SqlDialects {

  private SqlDialects() {}

  /** Nombre del producto en minúsculas, p. ej. {@code postgresql} o {@code h2}. */
  public static String product(DataSource dataSource) {
    try (Connection c = dataSource.getConnection()) {
      return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot detect the database product", e);
    }
  }

  public static boolean isH2(DataSource dataSource) {
    return product(dataSource).contains("h2");
  }
}
