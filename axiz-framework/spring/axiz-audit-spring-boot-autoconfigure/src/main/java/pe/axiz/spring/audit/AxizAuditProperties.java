package pe.axiz.spring.audit;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.audit.AuditSealer;

/**
 * Configuración de auditoría ({@code axiz.audit.*}).
 *
 * @param async publicación asíncrona con una cola acotada en un hilo virtual
 * @param http auditoría de las solicitudes HTTP entrantes
 * @param durable auditoría de los cambios de negocio escritos en el outbox dentro de su transacción
 */
@ConfigurationProperties("axiz.audit")
public record AxizAuditProperties(
    @DefaultValue Async async,
    @DefaultValue Http http,
    @DefaultValue Durable durable,
    @DefaultValue Integrity integrity,
    @DefaultValue Strict strict,
    @Nullable Mode mode) {
  /**
   * Cadenas de hash por defecto en local/test/ci; cualquier otro entorno debe usar HMAC, con un
   * secreto Base64 independiente y un id de rotación explícito (una cadena sin clave puede
   * recalcularse).
   */
  public record Integrity(
      @DefaultValue("HASH") AuditSealer.Mode mode,
      @Nullable String hmacSecret,
      @Nullable String hmacKeyId) {
    public Integrity {
      if (mode == AuditSealer.Mode.HMAC) {
        if (hmacSecret == null
            || hmacSecret.isBlank()
            || hmacSecret.contains("${")
            || hmacKeyId == null
            || !hmacKeyId.matches("[A-Za-z0-9_-]{1,64}"))
          throw new IllegalArgumentException(
              "Audit HMAC requires hmac-secret and a valid hmac-key-id");
      } else if (hmacSecret != null || hmacKeyId != null) {
        throw new IllegalArgumentException("Audit HMAC settings require integrity.mode=HMAC");
      }
    }
  }

  /**
   * Ruta confirmada independiente; nunca reutiliza una cola ni el respaldo tolerante de
   * publicación.
   */
  public record Strict(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("DIRECT") StrictMode mode,
      @DefaultValue("2s") Duration timeout,
      @DefaultValue("4") int concurrency,
      @DefaultValue("1m") Duration checkpointInterval) {
    public Strict {
      if (!timeout.isPositive()
          || timeout.compareTo(Duration.ofMinutes(1)) > 0
          || concurrency < 1
          || concurrency > 64
          || checkpointInterval.compareTo(Duration.ofSeconds(1)) < 0
          || checkpointInterval.compareTo(Duration.ofDays(1)) > 0)
        throw new IllegalArgumentException(
            "Invalid strict audit deadline, concurrency or checkpoint interval");
    }
  }

  /** DIRECT exige un destino atestiguado; DURABLE exige un commit independiente en el outbox. */
  public enum StrictMode {
    DIRECT,
    DURABLE
  }

  /** Garantía de entrega y persistencia de los eventos de auditoría. */
  public enum Mode {
    ASYNC,
    DURABLE,
    SYNC
  }

  public Mode effectiveMode() {
    return mode == null ? Mode.ASYNC : mode;
  }

  /**
   * Publicación asíncrona.
   *
   * @param capacity capacidad de la cola; cuando está llena, los eventos van al publicador de log
   *     en lugar de perderse
   * @param offerTimeout tiempo que un invocante espera a que haya espacio en la cola
   */
  public record Async(
      @DefaultValue("10000") int capacity,
      @DefaultValue("50ms") Duration offerTimeout,
      @DefaultValue("10s") Duration deliveryTimeout) {
    @ConstructorBinding
    public Async {
      if (capacity < 1 || offerTimeout.isNegative() || !deliveryTimeout.isPositive())
        throw new IllegalArgumentException("Invalid audit queue bounds");
    }
  }

  /**
   * Auditoría durable para evidencia financiera: las acciones exitosas registradas dentro de una
   * transacción se escriben en el outbox junto con el cambio de negocio (requiere la capacidad de
   * outbox) y las publica su relay; no se pierde nada si el proceso muere después del commit.
   *
   * @param destination destino lógico en el outbox de los eventos de auditoría (ver {@code
   *     axiz.messaging.destinations})
   * @param outsideTransaction {@code async}: los éxitos registrados fuera de una transacción usan
   *     el publicador asíncrono; {@code fail}: se rechazan
   */
  public record Durable(
      @DefaultValue("audit") String destination,
      @DefaultValue("async") AuditOutsideTransaction outsideTransaction,
      @DefaultValue("2s") Duration failureTimeout,
      @DefaultValue("4") int failureConcurrency) {
    @ConstructorBinding
    public Durable {
      if (destination == null || destination.isBlank() || destination.contains("${"))
        throw new IllegalArgumentException("Audit durable destination must not be blank");
      if (!failureTimeout.isPositive() || failureConcurrency < 1)
        throw new IllegalArgumentException("Invalid durable failure timeout or concurrency");
    }
  }

  /**
   * Auditoría HTTP.
   *
   * @param enabled indica si se auditan las solicitudes
   * @param methods métodos auditados
   * @param includedPaths patrones de ruta auditados
   * @param excludedPaths patrones de ruta que nunca se auditan
   */
  public record Http(
      @DefaultValue("false") boolean enabled,
      @DefaultValue({"POST", "PUT", "PATCH", "DELETE"}) List<String> methods,
      @DefaultValue("/**") List<String> includedPaths,
      @DefaultValue("/actuator/**") List<String> excludedPaths) {
    public Http {
      excludedPaths = List.copyOf(excludedPaths);
      includedPaths = List.copyOf(includedPaths);
      methods = List.copyOf(methods);
    }
  }
}
