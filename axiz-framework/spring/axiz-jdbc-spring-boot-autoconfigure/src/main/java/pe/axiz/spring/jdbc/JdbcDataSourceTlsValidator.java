package pe.axiz.spring.jdbc;

import javax.sql.DataSource;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/**
 * Integración explícita para un DataSource personalizado. Su fábrica también debe validar las
 * opciones efectivas antes de que un constructor o un método de arranque pueda conectarse; el
 * post-procesamiento no puede deshacer una E/S anterior.
 */
public interface JdbcDataSourceTlsValidator {
  boolean supports(DataSource source);

  void validate(DataSource source, DataTransportSecurityPolicy policy);
}
