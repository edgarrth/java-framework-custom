package pe.axiz.spring.saga;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;
import pe.axiz.core.saga.JdbcSagaStateStore;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaRecovery;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;

/** Saga orchestrator with state on Aurora, retries of transient failures and compensations. */
@AutoConfiguration(
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(AxizSagaProperties.class)
public class AxizSagaAutoConfiguration {

  @Bean
  SchemaInitializer axizSagaSchema(DataSource dataSource, AxizSagaProperties p) {
    return new SchemaInitializer(dataSource, "saga", p.initializeSchema());
  }

  @Bean
  @ConditionalOnMissingBean
  SagaStateStore axizSagaStateStore(
      DataSource dataSource, AxizSagaProperties p, SchemaInitializer axizSagaSchema) {
    return new JdbcSagaStateStore(new SpringConnectionProvider(dataSource), p.table());
  }

  @Bean
  @ConditionalOnMissingBean
  SagaOrchestrator axizSagaOrchestrator(
      SagaStateStore store,
      AxizSagaProperties p,
      ObjectProvider<SagaListener> listener,
      ObjectProvider<Clock> clock) {
    Backoff backoff = Backoff.exponential(p.initialDelay(), p.maxDelay());
    return new SagaOrchestrator(
        store,
        RetryPolicy.transientFailures(p.stepAttempts(), backoff),
        RetryPolicy.transientFailures(p.compensationAttempts(), backoff),
        listener.getIfAvailable(SagaListener::none),
        clock.getIfAvailable(Clock::systemUTC));
  }

  /** Resumption of sagas interrupted by a crash; services register their definitions on it. */
  @Bean
  @ConditionalOnMissingBean
  SagaRecovery axizSagaRecovery(
      SagaStateStore store, SagaOrchestrator orchestrator, ObjectProvider<Clock> clock) {
    return new SagaRecovery(store, orchestrator, clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  @ConditionalOnBean(MeterRegistry.class)
  static class MetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SagaListener axizMeteredSagaListener(MeterRegistry registry) {
      return new MeteredSagaListener(registry);
    }
  }
}
