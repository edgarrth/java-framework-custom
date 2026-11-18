package pe.axiz.spring.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.outbox.OutboxRelay;
import pe.axiz.core.outbox.OutboxRelayListener;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriteTracing;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;

/** Common outbox policy composed from persistence and messaging ports. */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.outbox.jdbc.AxizOutboxJdbcAutoConfiguration",
      "pe.axiz.spring.outbox.r2dbc.AxizOutboxR2dbcAutoConfiguration",
      "pe.axiz.spring.messaging.AxizMessagingAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.outbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizOutboxProperties.class)
@Import({OutboxRelayMetricsConfiguration.class, AxizOutboxPayloadAutoConfiguration.class})
public class AxizOutboxAutoConfiguration {
  @Bean
  SmartInitializingSingleton axizOutboxSchemaCheck(ObjectProvider<OutboxStore> stores) {
    return () -> {
      OutboxStore store = stores.getIfAvailable();
      if (store == null)
        throw new IllegalStateException(
            "Outbox requires an OutboxStore; select its integration and axiz.outbox.store");
      store.verifySchema();
    };
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxWriteTracing axizOutboxWriteTracing(ObjectProvider<ObservationRegistry> registry) {
    return OutboxTrace.tracing(registry.getIfAvailable(() -> ObservationRegistry.NOOP));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({EventFactory.class, OutboxWriter.class})
  TransactionalEvents axizTransactionalEvents(EventFactory factory, OutboxWriter writer) {
    return new TransactionalEvents(factory, writer);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({EventFactory.class, ReactiveOutboxWriter.class})
  ReactiveTransactionalEvents axizReactiveTransactionalEvents(
      EventFactory factory,
      ReactiveOutboxWriter writer,
      ObjectProvider<ObservationRegistry> registry) {
    return new ReactiveTransactionalEvents(
        factory, writer, registry.getIfAvailable(() -> ObservationRegistry.NOOP));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean({EventPublisher.class, OutboxRelayTransactions.class})
  @ConditionalOnBooleanProperty(name = "axiz.outbox.relay.enabled", matchIfMissing = true)
  static class RelayConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay axizOutboxRelay(
        OutboxStore store,
        EventPublisher publisher,
        OutboxRelayTransactions transactions,
        AxizOutboxProperties p,
        ObjectProvider<Clock> clock,
        ObjectProvider<OutboxRelayListener> listeners,
        AxizOutboxProtectionProperties protection,
        @Qualifier("axizOutboxPayloadProtector") PayloadProtector protector) {
      AxizOutboxProperties.Relay r = p.relay();
      var budget = OutboxLeaseValidation.budget(publisher, r).plus(protection.readBudget());
      OutboxLeaseValidation.validate(r.leaseDuration(), budget, r.claimAllowance());
      return OutboxRelay.builder(
              store, publisher, transactions.runner(), clock.getIfAvailable(Clock::systemUTC))
          .batchSize(r.batchSize())
          .maxAttempts(r.maxAttempts())
          .backoff(Backoff.exponential(r.initialDelay(), r.maxDelay()))
          .lease(r.leaseDuration())
          .publishBudget(budget)
          .listener(listeners.getIfAvailable(() -> OutboxRelayListener.NOOP))
          .protector(protector)
          .build();
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
  @ConditionalOnBean(OutboxStore.class)
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
