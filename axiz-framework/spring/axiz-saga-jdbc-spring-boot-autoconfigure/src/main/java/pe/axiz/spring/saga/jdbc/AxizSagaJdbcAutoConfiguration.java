package pe.axiz.spring.saga.jdbc;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.adapter.saga.jdbc.JdbcSagaStateStore;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;

/** JDBC integration selected explicitly by installing the provider starter. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.saga.AxizSagaAutoConfiguration",
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.saga.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "axiz.saga.store", havingValue = "jdbc", matchIfMissing = true)
@EnableConfigurationProperties(AxizSagaJdbcProperties.class)
public class AxizSagaJdbcAutoConfiguration {
  @Bean
  SchemaInitializer axizSagaSchema(DataSource dataSource, AxizSagaJdbcProperties p) {
    return new SchemaInitializer(dataSource, "saga", p.initializeSchema());
  }

  @Bean
  @ConditionalOnMissingBean
  SagaStateStore axizSagaStateStore(
      DataSource dataSource, AxizSagaJdbcProperties p, SchemaInitializer axizSagaSchema) {
    return new JdbcSagaStateStore(new SpringConnectionProvider(dataSource), p.table());
  }
}
