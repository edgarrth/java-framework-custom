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
 * JDBC integration selected explicitly by installing the provider starter. The store borrows its
 * own auto-commit connections: saga state and execution leases must be committed and visible to
 * other workers at once, whether or not the orchestrator runs inside an application transaction.
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
