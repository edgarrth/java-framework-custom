package pe.axiz.spring.saga;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaRecovery;
import pe.axiz.core.saga.SagaStateStore;

/** Políticas de sagas neutrales respecto del proveedor, recuperación y protección del contexto. */
@AutoConfiguration(afterName = "pe.axiz.spring.saga.jdbc.AxizSagaJdbcAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.saga.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizSagaProperties.class, AxizSagaProtectionProperties.class})
public class AxizSagaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  SagaOrchestrator axizSagaOrchestrator(
      SagaStateStore store,
      AxizSagaProperties p,
      ObjectProvider<SagaListener> listener,
      ObjectProvider<Clock> clock,
      AxizSagaProtectionProperties protection,
      ObjectProvider<EnvelopeEncryptor> envelope) {
    Backoff backoff = Backoff.exponential(p.initialDelay(), p.maxDelay());
    return new SagaOrchestrator(
        store,
        RetryPolicy.transientFailures(p.stepAttempts(), backoff),
        RetryPolicy.transientFailures(p.compensationAttempts(), backoff),
        listener.getIfAvailable(SagaListener::none),
        clock.getIfAvailable(Clock::systemUTC),
        protection.contextProtection().create(envelope::getObject),
        p.executionLease());
  }

  /**
   * Reanudación de las sagas interrumpidas por una caída; los servicios registran en ella sus
   * definiciones.
   */
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
