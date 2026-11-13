package pe.axiz.spring.jdbc;

import javax.sql.DataSource;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/**
 * Explicit integration for a custom DataSource. Its factory must also validate effective options
 * before a constructor or startup method can connect; post-processing cannot undo earlier I/O.
 */
public interface JdbcDataSourceTlsValidator {
  boolean supports(DataSource source);

  void validate(DataSource source, DataTransportSecurityPolicy policy);
}
