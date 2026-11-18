package pe.axiz.core.tokenization.vault;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.Token;

/** Restricted durable reconciliation reference, not a replay cache or an idempotency promise. */
public record UncertainTokenOperation(
    UUID operationId,
    VaultScope scope,
    Phase phase,
    @Nullable Token token,
    Instant at,
    Reason reason) {
  /** Mutation whose commit acknowledgement is missing. */
  public enum Phase {
    CREATE,
    DELETE,
    ROTATE
  }

  /** Closed technical reason catalog. */
  public enum Reason {
    STORAGE_ACK_UNKNOWN,
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
