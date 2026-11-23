package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Estado almacenado de una clave de idempotencia.
 *
 * @param key clave con alcance (operación + llamador + Idempotency-Key)
 * @param fingerprint hash de la petición (método, ruta, query y cuerpo)
 * @param status estado de procesamiento
 * @param owner token de la ejecución que tiene la clave; solo esa ejecución puede completarla o
 *     liberarla, de modo que una petición cuyo lock expiró no puede sobrescribir el resultado del
 *     siguiente propietario
 * @param response respuesta almacenada una vez completada
 * @param leaseUntil plazo del lease de ejecución, independiente de la retención
 * @param retainUntil plazo de retención para la deduplicación
 * @param resolutionVersion versión optimista, con fencing del propietario de la ejecución ante la
 *     reutilización de la clave
 * @param failure diagnóstico acotado sin contenido de la petición ni de la integración
 */
public record IdempotencyRecord(
    String key,
    String fingerprint,
    Status status,
    @Nullable String owner,
    @Nullable StoredResponse response,
    Instant leaseUntil,
    Instant retainUntil,
    long resolutionVersion,
    @Nullable String failure) {

  public IdempotencyRecord {
    Objects.requireNonNull(key);
    Objects.requireNonNull(fingerprint);
    Objects.requireNonNull(status);
    Objects.requireNonNull(leaseUntil);
    Objects.requireNonNull(retainUntil);
    if (resolutionVersion < 0) throw new IllegalArgumentException("negative version");
    if (failure != null && failure.length() > 256)
      throw new IllegalArgumentException("diagnostic too long");
  }

  /**
   * Plazo de retención; su expiración nunca significa que pueda tomarse el lease de una ejecución.
   */
  public Instant expiresAt() {
    return retainUntil;
  }

  public boolean liveAt(Instant now) {
    return retainUntil.isAfter(now);
  }

  public IdempotencyRecord completed(StoredResponse result, Instant until) {
    return IdempotencyRecord.builder(
            key, fingerprint, leaseUntil, until.isAfter(retainUntil) ? until : retainUntil)
        .status(Status.COMPLETED)
        .owner(owner)
        .response(result)
        .resolutionVersion(Math.addExact(resolutionVersion, 1))
        .failure(null)
        .build();
  }

  public IdempotencyRecord renewed(Instant lease, Instant until) {
    return IdempotencyRecord.builder(
            key,
            fingerprint,
            lease.isAfter(leaseUntil) ? lease : leaseUntil,
            until.isAfter(retainUntil) ? until : retainUntil)
        .status(status)
        .owner(owner)
        .response(response)
        .resolutionVersion(Math.addExact(resolutionVersion, 1))
        .failure(failure)
        .build();
  }

  public IdempotencyRecord unknown(String diagnostic) {
    return IdempotencyRecord.builder(key, fingerprint, leaseUntil, retainUntil)
        .status(Status.UNKNOWN)
        .owner(owner)
        .response(null)
        .resolutionVersion(Math.addExact(resolutionVersion, 1))
        .failure(diagnostic)
        .build();
  }

  /** {@code true} cuando {@code candidate} tiene este registro en curso. */
  public boolean heldBy(String candidate) {
    return status == Status.IN_PROGRESS && candidate.equals(owner);
  }

  /** Estado de procesamiento. */
  public enum Status {
    IN_PROGRESS,
    COMPLETED,
    UNKNOWN
  }

  /** Construcción nominal de una instantánea almacenada completa e inmutable. */
  public static Builder builder(
      String key, String fingerprint, Instant leaseUntil, Instant retainUntil) {
    return new Builder(key, fingerprint, leaseUntil, retainUntil);
  }

  /** Estado mutable de construcción; build valida y crea un valor inmutable independiente. */
  public static final class Builder {
    private final String key;
    private final String fingerprint;
    private final Instant leaseUntil;
    private final Instant retainUntil;
    private Status status = Status.IN_PROGRESS;
    private @Nullable String owner = null;
    private @Nullable StoredResponse response = null;
    private long resolutionVersion = 0;
    private @Nullable String failure = null;

    private Builder(String key, String fingerprint, Instant leaseUntil, Instant retainUntil) {
      this.key = Objects.requireNonNull(key);
      this.fingerprint = Objects.requireNonNull(fingerprint);
      this.leaseUntil = Objects.requireNonNull(leaseUntil);
      this.retainUntil = Objects.requireNonNull(retainUntil);
    }

    public Builder status(Status value) {
      this.status = value;
      return this;
    }

    public Builder owner(@Nullable String value) {
      this.owner = value;
      return this;
    }

    public Builder response(@Nullable StoredResponse value) {
      this.response = value;
      return this;
    }

    public Builder resolutionVersion(long value) {
      this.resolutionVersion = value;
      return this;
    }

    public Builder failure(@Nullable String value) {
      this.failure = value;
      return this;
    }

    public IdempotencyRecord build() {
      return new IdempotencyRecord(
          key,
          fingerprint,
          status,
          owner,
          response,
          leaseUntil,
          retainUntil,
          resolutionVersion,
          failure);
    }
  }
}
