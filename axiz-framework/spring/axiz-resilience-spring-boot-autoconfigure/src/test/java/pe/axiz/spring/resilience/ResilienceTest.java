package pe.axiz.spring.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
  void clientErrorsDoNotOpenTheCircuit() {
    assertTrue(ResiliencePolicies.isClientError(new BusinessException(CoreErrors.CONFLICT, "x")));
    assertFalse(ResiliencePolicies.isClientError(new IllegalStateException()));
  }
}
