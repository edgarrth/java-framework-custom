package pe.axiz.spring.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Registros de Resilience4j con las políticas de {@code axiz.resilience}, sus métricas de
 * Micrometer y {@code @Retryable} / {@code @ConcurrencyLimit} de Spring para los métodos de la
 * aplicación.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "axiz.resilience.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizResilienceProperties.class)
public class AxizResilienceAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  CircuitBreakerRegistry axizCircuitBreakerRegistry() {
    return CircuitBreakerRegistry.ofDefaults();
  }

  @Bean
  @ConditionalOnMissingBean
  BulkheadRegistry axizBulkheadRegistry() {
    return BulkheadRegistry.ofDefaults();
  }

  @Bean
  @ConditionalOnMissingBean
  ResiliencePolicies axizResiliencePolicies(
      AxizResilienceProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      BulkheadRegistry bulkheads) {
    return new ResiliencePolicies(properties, circuitBreakers, bulkheads);
  }

  /** Estado del circuit breaker, llamadas y uso del bulkhead como métricas. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics",
        "io.micrometer.core.instrument.MeterRegistry"
      })
  static class MetricsConfiguration {

    @Bean
    MeterBinder axizCircuitBreakerMetrics(CircuitBreakerRegistry registry) {
      return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    }

    @Bean
    MeterBinder axizBulkheadMetrics(BulkheadRegistry registry) {
      return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
    }
  }

  /** Métodos resilientes de Spring Framework 7. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBooleanProperty(name = "axiz.resilience.methods.enabled", matchIfMissing = true)
  @EnableResilientMethods
  static class ResilientMethodsConfiguration {}
}
