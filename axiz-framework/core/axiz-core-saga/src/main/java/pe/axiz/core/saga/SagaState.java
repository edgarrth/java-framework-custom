package pe.axiz.core.saga;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Estado persistido de una instancia de saga.
 *
 * @param sagaId id de la instancia
 * @param sagaName nombre de la definición
 * @param status estado
 * @param stepIndex siguiente paso a ejecutar (en ejecución) o último paso a compensar (compensando)
 * @param context contexto codificado
 * @param error último error
 * @param updatedAt última actualización
 * @param version versión del bloqueo optimista
 */
public record SagaState(
    String sagaId,
    String sagaName,
    SagaStatus status,
    int stepIndex,
    String context,
    @Nullable String error,
    Instant updatedAt,
    long version) {

  public SagaState {
    Objects.requireNonNull(sagaId);
    Objects.requireNonNull(sagaName);
    Objects.requireNonNull(status);
    Objects.requireNonNull(context);
    Objects.requireNonNull(updatedAt);
  }

  @Override
  public String toString() {
    return "SagaState[status=" + status + ", version=" + version + ", context=****]";
  }

  SagaState next(
      SagaStatus newStatus, int newStep, String newContext, @Nullable String newError, Instant at) {
    return SagaState.builder()
        .sagaId(sagaId)
        .sagaName(sagaName)
        .status(newStatus)
        .stepIndex(newStep)
        .context(newContext)
        .error(newError)
        .updatedAt(at)
        .version(version + 1)
        .build();
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable String sagaId = null;
    private @Nullable String sagaName = null;
    private @Nullable SagaStatus status = null;
    private int stepIndex = 0;
    private @Nullable String context = null;
    private @Nullable String error = null;
    private @Nullable Instant updatedAt = null;
    private long version = 0;

    private Builder() {}

    public Builder sagaId(String value) {
      this.sagaId = value;
      return this;
    }

    public Builder sagaName(String value) {
      this.sagaName = value;
      return this;
    }

    public Builder status(SagaStatus value) {
      this.status = value;
      return this;
    }

    public Builder stepIndex(int value) {
      this.stepIndex = value;
      return this;
    }

    public Builder context(String value) {
      this.context = value;
      return this;
    }

    public Builder error(@Nullable String value) {
      this.error = value;
      return this;
    }

    public Builder updatedAt(Instant value) {
      this.updatedAt = value;
      return this;
    }

    public Builder version(long value) {
      this.version = value;
      return this;
    }

    public SagaState build() {
      return new SagaState(
          Objects.requireNonNull(sagaId, "sagaId"),
          Objects.requireNonNull(sagaName, "sagaName"),
          Objects.requireNonNull(status, "status"),
          stepIndex,
          Objects.requireNonNull(context, "context"),
          error,
          Objects.requireNonNull(updatedAt, "updatedAt"),
          version);
    }
  }
}
