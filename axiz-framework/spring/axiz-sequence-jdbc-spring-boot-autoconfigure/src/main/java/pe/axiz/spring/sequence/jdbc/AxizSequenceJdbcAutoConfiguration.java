package pe.axiz.spring.sequence.jdbc;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import pe.axiz.adapter.sequence.jdbc.JdbcSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.jdbc.SchemaInitializer;
import pe.axiz.spring.jdbc.SpringConnectionProvider;
import pe.axiz.spring.jdbc.SpringTransactionRunner;
import pe.axiz.spring.sequence.AxizSequenceProperties;

/** JDBC sequence resources; common policies remain in the base capability. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.sequence.AxizSequenceAutoConfiguration",
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.sequence.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizSequenceJdbcProperties.class, AxizSequenceProperties.class})
@ConditionalOnImperativeApplication
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
@ConditionalOnMissingBean(SequenceStore.class)
@ConditionalOnProperty(name = "axiz.sequence.store", havingValue = "jdbc", matchIfMissing = true)
public class AxizSequenceJdbcAutoConfiguration {

  @Bean(destroyMethod = "close")
  JdbcSequenceConnections axizSequenceJdbcConnections(
      DataSource source,
      @Qualifier("axizSequenceDataSource") ObjectProvider<DataSource> dedicated,
      Environment environment) {
    return JdbcSequenceConnections.create(source, dedicated.getIfAvailable(), environment);
  }

  @Bean
  SchemaInitializer axizSequenceSchema(
      JdbcSequenceConnections resources, AxizSequenceJdbcProperties p) {
    return new SchemaInitializer(
        resources.connections(),
        "sequence",
        p.initializeSchema(),
        Map.of("axiz_sequence", p.table()));
  }

  @Bean
  @ConditionalOnMissingBean
  SequenceStore axizJdbcSequenceStore(
      JdbcSequenceConnections resources,
      AxizSequenceJdbcProperties p,
      SchemaInitializer axizSequenceSchema) {
    return new JdbcSequenceStore(
        new SpringConnectionProvider(resources.connections()),
        new SpringTransactionRunner(
            new DataSourceTransactionManager(resources.connections()),
            TransactionDefinition.PROPAGATION_REQUIRES_NEW),
        p.table());
  }
}
