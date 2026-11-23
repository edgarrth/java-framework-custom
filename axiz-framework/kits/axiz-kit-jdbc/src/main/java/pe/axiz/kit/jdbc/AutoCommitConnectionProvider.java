package pe.axiz.kit.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Toma prestada una conexión dedicada en modo auto-commit para cada operación y la cierra al
 * liberarla. Su trabajo nunca se une a una transacción de quien llama ni la espera: cada sentencia
 * hace commit por sí sola. La usan los almacenes cuyas escrituras deben ser visibles de inmediato
 * para otros workers (leases, propiedad, estado del proceso), de modo que se comportan igual dentro
 * o fuera de las transacciones de la aplicación.
 */
public final class AutoCommitConnectionProvider implements ConnectionProvider {

  private final DataSource dataSource;

  public AutoCommitConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public Connection get() throws SQLException {
    Connection c = dataSource.getConnection();
    try {
      if (!c.getAutoCommit()) {
        c.setAutoCommit(true);
      }
      return c;
    } catch (SQLException | RuntimeException e) {
      try {
        c.close();
      } catch (SQLException closing) {
        e.addSuppressed(closing);
      }
      throw e;
    }
  }

  @Override
  public boolean isTransactionActive(Connection connection) {
    return false;
  }

  @Override
  public void release(Connection connection) throws SQLException {
    connection.close();
  }
}
