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
 * Políticas de resiliencia ({@code axiz.resilience.*}). Toda dependencia recibe los valores por
 * defecto salvo que se declare una instancia con su nombre; una instancia es una política completa
 * (los campos omitidos toman los valores por defecto del framework, no los del servicio). Los
 * timeouts siempre están definidos.
 *
 * @param defaults política aplicada a cualquier dependencia sin entrada propia
 * @param instances políticas por nombre de dependencia (cliente HTTP, cliente SOAP, ...)
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
   * Política completa.
   *
   * @param timeouts timeouts de conexión, lectura y total
   * @param retry reintentos (solo para operaciones idempotentes)
   * @param circuitBreaker circuit breaker
   * @param bulkhead límite de llamadas concurrentes
   */
  public record Policy(
      @DefaultValue Timeouts timeouts,
      @DefaultValue Retry retry,
      @DefaultValue CircuitBreaker circuitBreaker,
      @DefaultValue Bulkhead bulkhead) {}

  /**
   * Timeouts.
   *
   * @param connect timeout de conexión
   * @param read timeout de lectura (respuesta)
   * @param total presupuesto global que incluye los reintentos
   */
  public record Timeouts(
      @DefaultValue("2s") Duration connect,
      @DefaultValue("5s") Duration read,
      @DefaultValue("15s") Duration total) {}

  /**
   * Reintentos con backoff exponencial y jitter.
   *
   * @param maxAttempts intentos incluido el primero (1 desactiva los reintentos)
   * @param initialDelay primer retraso
   * @param multiplier factor de crecimiento
   * @param maxDelay tope del retraso
   */
  public record Retry(
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("200ms") Duration initialDelay,
      @DefaultValue("2.0") double multiplier,
      @DefaultValue("2s") Duration maxDelay) {}

  /**
   * Circuit breaker (ventana deslizante basada en conteo).
   *
   * @param enabled indica si las llamadas pasan por un circuit breaker
   * @param failureRateThreshold porcentaje de fallos que abre el circuito
   * @param slowCallRateThreshold porcentaje de llamadas lentas que abre el circuito
   * @param slowCallDuration duración a partir de la cual una llamada es lenta
   * @param slidingWindowSize llamadas en la ventana
   * @param minimumNumberOfCalls llamadas antes de evaluar las tasas
   * @param waitInOpenState tiempo antes de intentar el estado semiabierto
   * @param permittedCallsInHalfOpenState llamadas de prueba en estado semiabierto
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
    /** Construcción con nombre; build comprueba los valores obligatorios y las invariantes. */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Estado mutable de construcción; se usa un builder por operación, nunca como estado
     * compartido.
     */
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
   * Bulkhead (semáforo).
   *
   * @param enabled indica si se limitan las llamadas concurrentes
   * @param maxConcurrentCalls llamadas concurrentes permitidas
   * @param maxWait tiempo que una llamada espera un permiso
   */
  public record Bulkhead(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("100") int maxConcurrentCalls,
      @DefaultValue("0ms") Duration maxWait) {}
}
