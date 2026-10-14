package pe.axiz.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL for {@code @SpringBootTest}: {@code @Import(PostgresTestConfiguration.class)}. The
 * service connection serves JDBC/JPA, R2DBC and Flyway alike.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer axizPostgresContainer() {
    return AxizContainers.postgres();
  }
}
