package pe.axiz.spring.sequence;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import pe.axiz.adapter.sequence.jdbc.JdbcSequenceStore;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;
import pe.axiz.spring.foundation.jdbc.SpringTransactionRunner;

/** Atomic business sequences on Aurora (counter table) or DynamoDB (atomic counter). */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "pe.axiz.spring.sequence.AxizSequenceR2dbcAutoConfiguration",
      "pe.axiz.spring.sequence.AxizSequenceDynamoDbAutoConfiguration"
    })
@EnableConfigurationProperties(AxizSequenceProperties.class)
public class AxizSequenceAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnImperativeApplication
  @ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
  @ConditionalOnMissingBean(SequenceStore.class)
  @ConditionalOnProperty(name = "axiz.sequence.store", havingValue = "jdbc", matchIfMissing = true)
  static class JdbcConfiguration {

    @Bean(destroyMethod = "close")
    SequencePools.Jdbc axizSequenceJdbcConnections(
        DataSource source,
        @Qualifier("axizSequenceDataSource") ObjectProvider<DataSource> dedicated,
        Environment environment) {
      return SequencePools.Jdbc.create(source, dedicated.getIfAvailable(), environment);
    }

    @Bean
    SchemaInitializer axizSequenceSchema(SequencePools.Jdbc resources, AxizSequenceProperties p) {
      return new SchemaInitializer(resources.connections(), "sequence", p.initializeSchema());
    }

    @Bean
    @ConditionalOnMissingBean
    SequenceStore axizJdbcSequenceStore(
        SequencePools.Jdbc resources,
        AxizSequenceProperties p,
        SchemaInitializer axizSequenceSchema) {
      return new JdbcSequenceStore(
          new SpringConnectionProvider(resources.connections()),
          new SpringTransactionRunner(
              new DataSourceTransactionManager(resources.connections()),
              TransactionDefinition.PROPAGATION_REQUIRES_NEW),
          p.table());
    }
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(SequenceStore.class)
  @ConditionalOnImperativeApplication
  Sequences axizSequences(
      AxizSequenceProperties p, SequenceStore store, ObjectProvider<Clock> clock) {
    return new Sequences(p, store, clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ReactiveSequences axizReactiveSequences(
        AxizSequenceProperties p,
        ObjectProvider<AsyncSequenceStore> stores,
        ObjectProvider<Clock> clock) {
      AsyncSequenceStore store = stores.getIfAvailable();
      if (store == null)
        throw new IllegalStateException(
            "Native asynchronous sequence store unavailable: " + p.store());
      return new ReactiveSequences(p, store, clock.getIfAvailable(Clock::systemUTC));
    }
  }
}
