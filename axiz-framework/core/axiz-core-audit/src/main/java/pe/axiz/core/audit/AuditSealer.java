package pe.axiz.core.audit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Completa un paso de la cadena sin retener su lock de estado durante la entrega. */
public final class AuditSealer {
  /** NONE desactiva los sellos; HASH detecta cambios; HMAC además requiere una clave protegida. */
  public enum Mode {
    NONE,
    HASH,
    HMAC
  }

  private final String chainId;
  private final Mode mode;
  private final AuditChainState state;
  private final AuditSealCalculator calculator;
  private final AtomicLong sequence = new AtomicLong();

  public AuditSealer(
      String chainId,
      Mode mode,
      AuditChainState state,
      @Nullable Supplier<byte[]> hmacKey,
      @Nullable String keyId) {
    AuditIntegrity.validateChain(chainId);
    this.chainId = chainId;
    this.mode = Objects.requireNonNull(mode);
    this.state = Objects.requireNonNull(state);
    this.calculator = new AuditSealCalculator(mode, hmacKey, keyId);
  }

  /**
   * Identidad del recurso de estado prestado, para validar la atomicidad en la frontera de
   * composición.
   */
  public @Nullable Object transactionResource() {
    return state.transactionResource();
  }

  /** Marca de nivel máximo operativa; incluye intentos sin commit y no es un checkpoint. */
  public long sequence() {
    return sequence.get();
  }

  public AuditEvent seal(AuditEvent unsealed) {
    return sealAttempt(unsealed, null);
  }

  public AuditEvent sealAgain(AuditEvent unsealed, AttemptRef previousAttempt) {
    return sealAttempt(unsealed, Objects.requireNonNull(previousAttempt));
  }

  private AuditEvent sealAttempt(AuditEvent unsealed, @Nullable AttemptRef previousAttempt) {
    if (unsealed.integrity() != null) throw new IllegalStateException("An event is already sealed");
    if (mode == Mode.NONE) return unsealed;
    AuditIntegrity seal =
        state.advance(chainId, head -> calculator.next(unsealed, head, previousAttempt));
    sequence.accumulateAndGet(seal.sequence(), Math::max);
    return unsealed.withIntegrity(seal);
  }
}
