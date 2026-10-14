package pe.axiz.spring.foundation.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/** Detects the database product to choose SQL dialects (PostgreSQL in clusters, H2 in tests). */
public final class SqlDialects {

  private SqlDialects() {}

  /** Lowercase product name, e.g. {@code postgresql} or {@code h2}. */
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
