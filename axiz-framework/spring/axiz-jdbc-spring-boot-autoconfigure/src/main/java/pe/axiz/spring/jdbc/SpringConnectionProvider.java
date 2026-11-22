package pe.axiz.spring.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.kit.jdbc.ConnectionProvider;

/**
 * Connection provider bound to Spring transactions: inside a transaction the transaction's
 * connection is returned and left open on release.
 */
public final class SpringConnectionProvider implements ConnectionProvider {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Spring transaction binding requires the exact injected DataSource identity. This "
              + "accessor exposes the borrowed transaction resource, not an owned pool lifecycle.")
  private final DataSource dataSource;

  public SpringConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Spring transaction binding requires the exact borrowed DataSource identity, not a copied"
              + " resource.")
  public DataSource dataSource() {
    return dataSource;
  }

  @Override
  public Object transactionResource() {
    return dataSource();
  }

  @Override
  public boolean isTransactionActive(Connection connection) throws SQLException {
    return TransactionSynchronizationManager.isActualTransactionActive()
        && DataSourceUtils.isConnectionTransactional(connection, dataSource)
        && !connection.getAutoCommit();
  }

  @Override
  public Connection get() {
    return DataSourceUtils.getConnection(dataSource);
  }

  @Override
  public void release(Connection connection) {
    DataSourceUtils.releaseConnection(connection, dataSource);
  }
}
