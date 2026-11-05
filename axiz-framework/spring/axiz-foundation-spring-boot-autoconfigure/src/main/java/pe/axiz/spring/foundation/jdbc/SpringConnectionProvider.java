package pe.axiz.spring.foundation.jdbc;

import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import pe.axiz.kit.jdbc.ConnectionProvider;

/**
 * Connection provider bound to Spring transactions: inside a transaction the transaction's
 * connection is returned and left open on release.
 */
public final class SpringConnectionProvider implements ConnectionProvider {

  private final DataSource dataSource;

  public SpringConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  public DataSource dataSource() {
    return dataSource;
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
