package pe.axiz.spring.saga.jdbc;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.adapter.saga.jdbc.JdbcSagaStateStore;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.kit.jdbc.AutoCommitConnectionProvider;
import pe.axiz.spring.jdbc.SchemaInitializer;

/**
 * Integración JDBC seleccionada explícitamente al instalar el starter del proveedor. El almacén
 * toma prestadas sus propias conexiones con auto-commit: el estado de la saga y los leases de
 * ejecución deben confirmarse y ser visibles para los demás workers de inmediato, se ejecute o no
 * el orquestador dentro de una transacción de la aplicación.
 */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.saga.AxizSagaAutoConfiguration",
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.saga.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "axiz.saga.store", havingValue = "jdbc", matchIfMissing = true)
@EnableConfigurationProperties(AxizSagaJdbcProperties.class)
public class AxizSagaJdbcAutoConfiguration {
  @Bean
  SchemaInitializer axizSagaSchema(DataSource dataSource, AxizSagaJdbcProperties p) {
    return new SchemaInitializer(
        dataSource, "saga", p.initializeSchema(), Map.of("axiz_saga", p.table()));
  }

  @Bean
  @ConditionalOnMissingBean
  SagaStateStore axizSagaStateStore(
      DataSource dataSource, AxizSagaJdbcProperties p, SchemaInitializer axizSagaSchema) {
    return new JdbcSagaStateStore(new AutoCommitConnectionProvider(dataSource), p.table());
  }
}
