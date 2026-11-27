package pe.axiz.core.tokenization.vault;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.Token;

/**
 * Referencia de reconciliación durable y restringida, no una caché de replay ni una promesa de
 * idempotencia.
 */
public record UncertainTokenOperation(
    UUID operationId,
    VaultScope scope,
    Phase phase,
    @Nullable Token token,
    Instant at,
    Reason reason) {
  /** Mutación cuyo acuse de commit falta. */
  public enum Phase {
    CREATE,
    DELETE,
    ROTATE
  }

  /** Catálogo cerrado de motivos técnicos. */
  public enum Reason {
    STORAGE_ACK_UNKNOWN,
    COMMITTED_AUDIT_UNCONFIRMED,
    CANCELLED_DURING_WRITE
  }

  public UncertainTokenOperation {
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(scope);
    Objects.requireNonNull(phase);
    Objects.requireNonNull(at);
    Objects.requireNonNull(reason);
  }

  @Override
  public String toString() {
    return "UncertainTokenOperation[" + operationId + ", " + phase + "]";
  }
}
