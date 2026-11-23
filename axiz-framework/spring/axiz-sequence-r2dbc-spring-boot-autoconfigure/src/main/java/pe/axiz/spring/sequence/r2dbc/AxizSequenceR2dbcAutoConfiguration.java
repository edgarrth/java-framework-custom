package pe.axiz.spring.sequence.r2dbc;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.sequence.r2dbc.R2dbcSequenceStore;
import pe.axiz.adapter.sequence.r2dbc.ReactiveSequenceStore;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.spring.sequence.AxizSequenceAutoConfiguration;
import pe.axiz.spring.sequence.AxizSequenceProperties;
import pe.axiz.spring.sequence.ReactiveSequences;

/**
 * Las reservas de secuencias de PostgreSQL siguen siendo durables cuando una transacción de negocio
 * hace rollback.
 */
@AutoConfiguration(
    before = AxizSequenceAutoConfiguration.class,
    afterName = "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration")
@ConditionalOnClass({ConnectionFactory.class, ReactiveSequenceStore.class, ConnectionPool.class})
@ConditionalOnBean(ConnectionFactory.class)
@ConditionalOnMissingBean({AsyncSequenceStore.class, ReactiveSequences.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = "axiz.sequence.store", havingValue = "r2dbc")
@ConditionalOnBooleanProperty(name = "axiz.sequence.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizSequenceR2dbcProperties.class, AxizSequenceProperties.class})
public class AxizSequenceR2dbcAutoConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ReactiveSequenceStore.class)
  R2dbcSequenceConnections axizSequenceReactiveConnections(
      ConnectionFactory source,
      @Qualifier("axizSequenceConnectionFactory") ObjectProvider<ConnectionFactory> dedicated,
      Environment environment) {
    return R2dbcSequenceConnections.create(source, dedicated.getIfAvailable(), environment);
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveSequenceStore axizR2dbcSequenceStore(
      R2dbcSequenceConnections resources, AxizSequenceR2dbcProperties properties) {
    return new R2dbcSequenceStore(resources.connections(), properties.table());
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveSequences axizR2dbcSequences(
      AxizSequenceProperties properties, ReactiveSequenceStore store, ObjectProvider<Clock> clock) {
    // Una reserva de contador posee intencionalmente su transacción. Este puente privado solo
    // comparte el algoritmo de asignación del JDK; el contrato público de R2DBC sigue siendo Mono y
    // nunca se une a una transacción de negocio ni la transporta mediante CompletionStage.
    return new ReactiveSequences(
        properties,
        (name, period, amount) ->
            Stages.requireValue(store.increment(name, period, amount).toFuture()),
        clock.getIfAvailable(Clock::systemUTC));
  }
}
