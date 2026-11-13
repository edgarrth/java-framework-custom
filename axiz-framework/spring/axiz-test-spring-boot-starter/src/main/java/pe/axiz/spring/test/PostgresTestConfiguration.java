package pe.axiz.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
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

  @Bean
  DynamicPropertyRegistrar axizPostgresTransport() {
    return registry -> {
      registry.add("spring.datasource.hikari.data-source-properties.sslmode", () -> "disable");
      registry.add("spring.flyway.jdbc-properties.sslmode", () -> "disable");
      registry.add("spring.r2dbc.properties.sslMode", () -> "disable");
    };
  }
}
