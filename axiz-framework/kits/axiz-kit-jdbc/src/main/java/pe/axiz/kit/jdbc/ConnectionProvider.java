package pe.axiz.kit.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * Suministra la conexión JDBC a usar. Dentro de una transacción devuelve la conexión de la
 * transacción (Spring: {@code DataSourceUtils}); {@link #release(Connection)} no debe cerrarla.
 */
public interface ConnectionProvider {

  /**
   * Identidad del recurso enlistado en las transacciones; null para los proveedores sin ese
   * contrato.
   */
  default @Nullable Object transactionResource() {
    return null;
  }

  /** Indica si esta conexión participa en la transacción activa de quien llama. */
  default boolean isTransactionActive(Connection connection) throws SQLException {
    return !connection.getAutoCommit();
  }

  Connection get() throws SQLException;

  void release(Connection connection) throws SQLException;
}
