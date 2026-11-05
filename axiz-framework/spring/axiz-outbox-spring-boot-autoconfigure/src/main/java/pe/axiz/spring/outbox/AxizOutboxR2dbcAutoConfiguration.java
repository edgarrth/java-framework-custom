package pe.axiz.spring.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.R2dbcTransactionRunner;
import pe.axiz.kit.r2dbc.ReactiveSql;

/**
 * Transactional outbox for reactive services on Aurora over R2DBC. Business code enqueues with
 * {@link ReactiveTransactionalEvents} inside its reactive transaction; the relay runs on a virtual
 * thread with its own connections and transactions ({@code FOR UPDATE SKIP LOCKED}), exactly like
 * the JDBC relay. Active when the service has an R2DBC connection factory and no DataSource.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
      "pe.axiz.spring.r2dbc.AxizR2dbcAutoConfiguration",
      "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration"
    })
@ConditionalOnClass(
    name = {"pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore", "io.r2dbc.spi.ConnectionFactory"})
@ConditionalOnBean({ConnectionFactory.class, ReactiveSql.class})
@ConditionalOnMissingBean(DataSource.class)
@ConditionalOnBooleanProperty(name = "axiz.outbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizOutboxProperties.class)
public class AxizOutboxR2dbcAutoConfiguration {

  /** Store used by the relay: statements run on the connection bound by its transactions. */
  @Bean
  SmartInitializingSingleton axizOutboxSchemaCheck(
      OutboxStore store, AxizOutboxProperties properties, Environment environment) {
    return () -> {
      OutboxLeaseValidation.validate(properties, environment);
      store.verifySchema();
    };
  }

  @Bean
  @ConditionalOnMissingBean(OutboxStore.class)
  R2dbcOutboxStore axizOutboxStore(ConnectionFactory connectionFactory, AxizOutboxProperties p) {
    return new R2dbcOutboxStore(new R2dbcSql(connectionFactory), p.table(), p.statementTimeout());
  }

  /** Writer used by business code: joins the caller's reactive transaction. */
  @Bean
  @ConditionalOnMissingBean
  ReactiveOutboxWriter axizReactiveOutboxWriter(
      ReactiveSql sql, AxizOutboxProperties p, ObjectProvider<Clock> clock) {
    return new ReactiveOutboxWriter(
        new R2dbcOutboxStore(sql, p.table(), p.statementTimeout()),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(EventFactory.class)
  ReactiveTransactionalEvents axizReactiveTransactionalEvents(
      EventFactory factory,
      ReactiveOutboxWriter writer,
      ObjectProvider<ObservationRegistry> registry) {
    return new ReactiveTransactionalEvents(
        factory, writer, registry.getIfAvailable(() -> ObservationRegistry.NOOP));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(EventPublisher.class)
  @ConditionalOnBooleanProperty(name = "axiz.outbox.relay.enabled", matchIfMissing = true)
  static class RelayConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay axizOutboxRelay(
        OutboxStore store,
        EventPublisher publisher,
        ConnectionFactory connectionFactory,
        AxizOutboxProperties p,
        ObjectProvider<Clock> clock) {
      AxizOutboxProperties.Relay r = p.relay();
      return new OutboxRelay(
          store,
          publisher,
          new R2dbcTransactionRunner(connectionFactory, p.statementTimeout()),
          clock.getIfAvailable(Clock::systemUTC),
          r.batchSize(),
          r.maxAttempts(),
          Backoff.exponential(r.initialDelay(), r.maxDelay()),
          r.leaseDuration());
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

  /** {@code axiz.outbox.pending}, refreshed off the scrape thread. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class MetricsConfiguration {

    @Bean
    MeterBinder axizOutboxMetrics(OutboxStore store, ObjectProvider<Clock> clock) {
      CachedPendingCount pending =
          new CachedPendingCount(
              store, Duration.ofSeconds(15), clock.getIfAvailable(Clock::systemUTC));
      return registry ->
          Gauge.builder("axiz.outbox.pending", pending, CachedPendingCount::value)
              .description("Outbox records waiting to be published")
              .register(registry);
    }
  }
}
