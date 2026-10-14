package pe.axiz.spring.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.outbox.Dialect;
import pe.axiz.core.outbox.JdbcOutboxStore;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;
import pe.axiz.spring.foundation.jdbc.SpringTransactionRunner;
import pe.axiz.spring.foundation.jdbc.SqlDialects;

/**
 * Transactional outbox: events written in the business transaction and relayed at-least-once to
 * MSK, SNS or SQS through the messaging starter's publisher.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration"
    })
@ConditionalOnBean(DataSource.class)
@ConditionalOnBooleanProperty(name = "axiz.outbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizOutboxProperties.class)
public class AxizOutboxAutoConfiguration {

  @Bean
  SchemaInitializer axizOutboxSchema(DataSource dataSource, AxizOutboxProperties p) {
    return new SchemaInitializer(dataSource, "outbox", p.initializeSchema());
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxStore axizOutboxStore(
      DataSource dataSource, AxizOutboxProperties p, SchemaInitializer axizOutboxSchema) {
    Dialect dialect = SqlDialects.isH2(dataSource) ? Dialect.H2 : Dialect.POSTGRESQL;
    return new JdbcOutboxStore(new SpringConnectionProvider(dataSource), p.table(), dialect);
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxWriter axizOutboxWriter(OutboxStore store, ObjectProvider<Clock> clock) {
    return new OutboxWriter(store, clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(EventFactory.class)
  TransactionalEvents axizTransactionalEvents(EventFactory factory, OutboxWriter writer) {
    return new TransactionalEvents(factory, writer);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean({EventPublisher.class, PlatformTransactionManager.class})
  @ConditionalOnBooleanProperty(name = "axiz.outbox.relay.enabled", matchIfMissing = true)
  static class RelayConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay axizOutboxRelay(
        OutboxStore store,
        EventPublisher publisher,
        PlatformTransactionManager tx,
        AxizOutboxProperties p,
        ObjectProvider<Clock> clock) {
      AxizOutboxProperties.Relay r = p.relay();
      return new OutboxRelay(
          store,
          publisher,
          new SpringTransactionRunner(tx),
          clock.getIfAvailable(Clock::systemUTC),
          r.batchSize(),
          r.maxAttempts(),
          Backoff.exponential(r.initialDelay(), r.maxDelay()));
    }

    @Bean
    OutboxRelayRunner axizOutboxRelayRunner(
        OutboxRelay relay,
        OutboxStore store,
        ObjectProvider<ObservationRegistry> registry,
        AxizOutboxProperties p,
        ObjectProvider<Clock> clock) {
      return new OutboxRelayRunner(
          relay,
          store,
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          p.relay(),
          clock.getIfAvailable(Clock::systemUTC));
    }
  }

  /** {@code axiz.outbox.pending}: records waiting to be published. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class MetricsConfiguration {

    @Bean
    MeterBinder axizOutboxMetrics(OutboxStore store) {
      return registry ->
          Gauge.builder(
                  "axiz.outbox.pending",
                  store,
                  s -> {
                    try {
                      return s.countPending();
                    } catch (RuntimeException e) {
                      return Double.NaN;
                    }
                  })
              .description("Outbox records waiting to be published")
              .register(registry);
    }
  }
}
