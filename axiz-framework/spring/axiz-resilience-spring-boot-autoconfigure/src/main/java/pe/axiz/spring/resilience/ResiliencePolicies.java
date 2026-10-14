package pe.axiz.spring.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;
import pe.axiz.core.resilience.TimeoutSettings;

/**
 * Resolves the resilience components of a named dependency from {@code axiz.resilience}. Circuit
 * breakers and bulkheads are created once per name and exported as metrics.
 */
public class ResiliencePolicies {

  private final AxizResilienceProperties properties;
  private final CircuitBreakerRegistry circuitBreakers;
  private final BulkheadRegistry bulkheads;

  public ResiliencePolicies(
      AxizResilienceProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      BulkheadRegistry bulkheads) {
    this.properties = Objects.requireNonNull(properties);
    this.circuitBreakers = Objects.requireNonNull(circuitBreakers);
    this.bulkheads = Objects.requireNonNull(bulkheads);
  }

  public TimeoutSettings timeouts(String name) {
    AxizResilienceProperties.Timeouts t = properties.policy(name).timeouts();
    return new TimeoutSettings(t.connect(), t.read(), t.total());
  }

  /** Retries of transient failures; callers apply it only to idempotent operations. */
  public RetryPolicy retry(String name) {
    AxizResilienceProperties.Retry r = properties.policy(name).retry();
    if (r.maxAttempts() <= 1) {
      return RetryPolicy.none();
    }
    return RetryPolicy.transientFailures(
        r.maxAttempts(), new Backoff(r.initialDelay(), r.multiplier(), r.maxDelay(), true));
  }

  public Optional<CircuitBreaker> circuitBreaker(String name) {
    AxizResilienceProperties.CircuitBreaker c = properties.policy(name).circuitBreaker();
    if (!c.enabled()) {
      return Optional.empty();
    }
    return Optional.of(
        circuitBreakers.circuitBreaker(
            name,
            () ->
                CircuitBreakerConfig.custom()
                    .failureRateThreshold(c.failureRateThreshold())
                    .slowCallRateThreshold(c.slowCallRateThreshold())
                    .slowCallDurationThreshold(c.slowCallDuration())
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(c.slidingWindowSize())
                    .minimumNumberOfCalls(c.minimumNumberOfCalls())
                    .waitDurationInOpenState(c.waitInOpenState())
                    .permittedNumberOfCallsInHalfOpenState(c.permittedCallsInHalfOpenState())
                    .automaticTransitionFromOpenToHalfOpenEnabled(true)
                    .ignoreException(ResiliencePolicies::isClientError)
                    .build()));
  }

  public Optional<Bulkhead> bulkhead(String name) {
    AxizResilienceProperties.Bulkhead b = properties.policy(name).bulkhead();
    if (!b.enabled()) {
      return Optional.empty();
    }
    return Optional.of(
        bulkheads.bulkhead(
            name,
            () ->
                BulkheadConfig.custom()
                    .maxConcurrentCalls(b.maxConcurrentCalls())
                    .maxWaitDuration(b.maxWait())
                    .build()));
  }

  /** 4xx answers from a dependency are the caller's problem and must not open the circuit. */
  static boolean isClientError(Throwable t) {
    return t instanceof pe.axiz.core.errors.AxizException a
        && !a.category().retryable()
        && a.category().httpStatus() < 500;
  }
}
