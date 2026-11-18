package pe.axiz.core.audit;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Completes one chain step without holding its state lock during delivery. */
public final class AuditSealer {
  /** NONE disables seals; HASH detects changes; HMAC additionally requires a protected key. */
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

  /** Borrowed state resource identity, for atomicity validation at the composition boundary. */
  public @Nullable Object transactionResource() {
    return state.transactionResource();
  }

  /** Operational high-water mark; it includes uncommitted attempts and is not a checkpoint. */
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
