package pe.axiz.spring.sequence;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.core.sequence.JdbcSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;
import pe.axiz.spring.foundation.jdbc.SpringTransactionRunner;

/** Atomic business sequences on Aurora (counter table) or DynamoDB (atomic counter). */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "pe.axiz.spring.sequence.AxizSequenceDynamoDbAutoConfiguration"
    })
@EnableConfigurationProperties(AxizSequenceProperties.class)
public class AxizSequenceAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
  @ConditionalOnProperty(name = "axiz.sequence.store", havingValue = "jdbc", matchIfMissing = true)
  static class JdbcConfiguration {

    @Bean
    SchemaInitializer axizSequenceSchema(DataSource dataSource, AxizSequenceProperties p) {
      return new SchemaInitializer(dataSource, "sequence", p.initializeSchema());
    }

    @Bean
    @ConditionalOnMissingBean
    SequenceStore axizJdbcSequenceStore(
        DataSource ds,
        PlatformTransactionManager tx,
        AxizSequenceProperties p,
        SchemaInitializer axizSequenceSchema) {
      return new JdbcSequenceStore(
          new SpringConnectionProvider(ds), new SpringTransactionRunner(tx), p.table());
    }
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(SequenceStore.class)
  Sequences axizSequences(
      AxizSequenceProperties p, SequenceStore store, ObjectProvider<Clock> clock) {
    return new Sequences(p, store, clock.getIfAvailable(Clock::systemUTC));
  }
}
