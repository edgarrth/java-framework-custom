package pe.axiz.spring.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;

class ResilienceTest {

  @Test
  void instancesOverrideDefaults() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizResilienceAutoConfiguration.class))
        .withPropertyValues(
            "axiz.resilience.instances.bank.timeouts.read=20s",
            "axiz.resilience.instances.bank.timeouts.total=30s",
            "axiz.resilience.instances.bank.retry.max-attempts=1",
            "axiz.resilience.instances.bank.circuit-breaker.enabled=false")
        .run(
            ctx -> {
              ResiliencePolicies p = ctx.getBean(ResiliencePolicies.class);
              assertEquals(Duration.ofSeconds(20), p.timeouts("bank").read());
              assertEquals(Duration.ofSeconds(5), p.timeouts("other").read());
              assertEquals(1, p.retry("bank").maxAttempts());
              assertEquals(3, p.retry("other").maxAttempts());
              assertTrue(p.circuitBreaker("bank").isEmpty());
              assertTrue(p.circuitBreaker("other").isPresent());
              assertTrue(p.bulkhead("other").isPresent());
            });
  }

  @Test
  void programmaticPolicyBuildsAnEffectiveCircuitAndIgnoresBusinessRejections() {
    var breaker =
        AxizResilienceProperties.CircuitBreaker.builder()
            .enabled(true)
            .failureRateThreshold(50)
            .slowCallRateThreshold(80)
            .slowCallDuration(Duration.ofSeconds(5))
            .slidingWindowSize(10)
            .minimumNumberOfCalls(2)
            .waitInOpenState(Duration.ofMinutes(1))
            .permittedCallsInHalfOpenState(3)
            .build();
    var policy =
        new AxizResilienceProperties.Policy(
            new AxizResilienceProperties.Timeouts(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)),
            new AxizResilienceProperties.Retry(1, Duration.ofMillis(10), 2, Duration.ofSeconds(1)),
            breaker,
            new AxizResilienceProperties.Bulkhead(false, 1, Duration.ZERO));
    var policies =
        new ResiliencePolicies(
            new AxizResilienceProperties(policy, Map.of()),
            CircuitBreakerRegistry.ofDefaults(),
            BulkheadRegistry.ofDefaults());
    var actual = policies.circuitBreaker("bank").orElseThrow();
    actual.onError(
        1, TimeUnit.MILLISECONDS, new BusinessException(CoreErrors.CONFLICT, "conflict"));
    assertEquals(0, actual.getMetrics().getNumberOfBufferedCalls());
    actual.onError(1, TimeUnit.MILLISECONDS, new IllegalStateException("unavailable"));
    actual.onSuccess(1, TimeUnit.MILLISECONDS);
    assertEquals(CircuitBreaker.State.OPEN, actual.getState());
    assertEquals(10, actual.getCircuitBreakerConfig().getSlidingWindowSize());
    assertEquals(80, actual.getCircuitBreakerConfig().getSlowCallRateThreshold());
    assertEquals(
        Duration.ofSeconds(5), actual.getCircuitBreakerConfig().getSlowCallDurationThreshold());
    assertEquals(3, actual.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState());
    assertEquals(
        60_000, actual.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
    assertTrue(policies.bulkhead("bank").isEmpty());
  }

  @Test
  void clientErrorsDoNotOpenTheCircuit() {
    assertTrue(ResiliencePolicies.isClientError(new BusinessException(CoreErrors.CONFLICT, "x")));
    assertFalse(ResiliencePolicies.isClientError(new IllegalStateException()));
  }
}
