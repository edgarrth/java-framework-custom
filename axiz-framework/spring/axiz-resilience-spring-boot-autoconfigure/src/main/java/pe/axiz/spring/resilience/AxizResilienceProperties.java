package pe.axiz.spring.resilience;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resilience policies ({@code axiz.resilience.*}). Every dependency gets the defaults unless an
 * instance with its name is declared; an instance is a complete policy (omitted fields take the
 * framework defaults, not the service defaults). Timeouts are always defined.
 *
 * @param defaults policy applied to any dependency without its own entry
 * @param instances policies by dependency name (HTTP client, SOAP client, ...)
 */
@ConfigurationProperties("axiz.resilience")
public record AxizResilienceProperties(
    @DefaultValue Policy defaults, @DefaultValue Map<String, Policy> instances) {
  public AxizResilienceProperties {
    instances = Collections.unmodifiableMap(new LinkedHashMap<>(instances));
  }

  public Policy policy(String name) {
    return instances.getOrDefault(name, defaults);
  }

  /**
   * A complete policy.
   *
   * @param timeouts connect, read and total timeouts
   * @param retry retries (only for idempotent operations)
   * @param circuitBreaker circuit breaker
   * @param bulkhead concurrent call limit
   */
  public record Policy(
      @DefaultValue Timeouts timeouts,
      @DefaultValue Retry retry,
      @DefaultValue CircuitBreaker circuitBreaker,
      @DefaultValue Bulkhead bulkhead) {}

  /**
   * Timeouts.
   *
   * @param connect connection timeout
   * @param read read (response) timeout
   * @param total overall budget including retries
   */
  public record Timeouts(
      @DefaultValue("2s") Duration connect,
      @DefaultValue("5s") Duration read,
      @DefaultValue("15s") Duration total) {}

  /**
   * Retries with exponential backoff and jitter.
   *
   * @param maxAttempts attempts including the first one (1 disables retries)
   * @param initialDelay first delay
   * @param multiplier growth factor
   * @param maxDelay delay cap
   */
  public record Retry(
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("200ms") Duration initialDelay,
      @DefaultValue("2.0") double multiplier,
      @DefaultValue("2s") Duration maxDelay) {}

  /**
   * Circuit breaker (count-based sliding window).
   *
   * @param enabled whether calls go through a circuit breaker
   * @param failureRateThreshold failure percentage that opens the circuit
   * @param slowCallRateThreshold slow-call percentage that opens the circuit
   * @param slowCallDuration duration from which a call is slow
   * @param slidingWindowSize calls in the window
   * @param minimumNumberOfCalls calls before rates are evaluated
   * @param waitInOpenState time before trying half-open
   * @param permittedCallsInHalfOpenState trial calls in half-open
   */
  public record CircuitBreaker(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("50") float failureRateThreshold,
      @DefaultValue("80") float slowCallRateThreshold,
      @DefaultValue("5s") Duration slowCallDuration,
      @DefaultValue("50") int slidingWindowSize,
      @DefaultValue("20") int minimumNumberOfCalls,
      @DefaultValue("30s") Duration waitInOpenState,
      @DefaultValue("5") int permittedCallsInHalfOpenState) {
    /** Named construction; required values and invariants are checked by build. */
    public static Builder builder() {
      return new Builder();
    }

    /** Mutable construction state; use one builder per operation, never as shared state. */
    public static final class Builder {
      private boolean enabled = false;
      private float failureRateThreshold = 0;
      private float slowCallRateThreshold = 0;
      private @Nullable Duration slowCallDuration = null;
      private int slidingWindowSize = 0;
      private int minimumNumberOfCalls = 0;
      private @Nullable Duration waitInOpenState = null;
      private int permittedCallsInHalfOpenState = 0;

      private Builder() {}

      public Builder enabled(boolean value) {
        this.enabled = value;
        return this;
      }

      public Builder failureRateThreshold(float value) {
        this.failureRateThreshold = value;
        return this;
      }

      public Builder slowCallRateThreshold(float value) {
        this.slowCallRateThreshold = value;
        return this;
      }

      public Builder slowCallDuration(Duration value) {
        this.slowCallDuration = value;
        return this;
      }

      public Builder slidingWindowSize(int value) {
        this.slidingWindowSize = value;
        return this;
      }

      public Builder minimumNumberOfCalls(int value) {
        this.minimumNumberOfCalls = value;
        return this;
      }

      public Builder waitInOpenState(Duration value) {
        this.waitInOpenState = value;
        return this;
      }

      public Builder permittedCallsInHalfOpenState(int value) {
        this.permittedCallsInHalfOpenState = value;
        return this;
      }

      public CircuitBreaker build() {
        return new CircuitBreaker(
            enabled,
            failureRateThreshold,
            slowCallRateThreshold,
            Objects.requireNonNull(slowCallDuration, "slowCallDuration"),
            slidingWindowSize,
            minimumNumberOfCalls,
            Objects.requireNonNull(waitInOpenState, "waitInOpenState"),
            permittedCallsInHalfOpenState);
      }
    }
  }

  /**
   * Bulkhead (semaphore).
   *
   * @param enabled whether concurrent calls are limited
   * @param maxConcurrentCalls concurrent calls allowed
   * @param maxWait time a call waits for a permit
   */
  public record Bulkhead(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("100") int maxConcurrentCalls,
      @DefaultValue("0ms") Duration maxWait) {}
}
