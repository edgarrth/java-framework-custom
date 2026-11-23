package pe.axiz.spring.audit;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AttemptOutcome;
import pe.axiz.core.audit.AttemptRef;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.commons.TransactionOutcome;

/** Un hecho inmutable y la evidencia disponible sobre su intento independiente de persistencia. */
final class DurableAuditAttempt {
  private final AuditEvent original;
  private final AtomicReference<@Nullable AuditEvent> signed = new AtomicReference<>();
  private final AtomicReference<TransactionOutcome> completion =
      new AtomicReference<>(TransactionOutcome.UNKNOWN);
  private final AtomicBoolean cancelled = new AtomicBoolean();

  DurableAuditAttempt(AuditEvent original) {
    if (original.integrity() != null)
      throw new IllegalArgumentException("Expected an unsealed fact");
    this.original = Objects.requireNonNull(original);
  }

  void requireRunning() {
    if (cancelled.get() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Audit persistence cancelled");
  }

  AuditEvent remember(AuditEvent event) {
    if (!signed.compareAndSet(null, event))
      throw new IllegalStateException("Attempt already sealed");
    requireRunning();
    return event;
  }

  void cancel() {
    cancelled.set(true);
  }

  void complete(TransactionOutcome outcome) {
    completion.set(outcome);
  }

  boolean committed() {
    return completion.get() == TransactionOutcome.COMMITTED;
  }

  AuditEvent fallback(AuditSealer degradation) {
    TransactionOutcome observed = completion.get();
    AuditEvent attempted = signed.get();
    var seal = attempted == null ? null : attempted.integrity();
    if (seal == null) return degradation.seal(original).withPersistence("NOT_CONFIRMED");
    boolean rolledBack = observed == TransactionOutcome.ROLLED_BACK;
    return degradation
        .sealAgain(
            original,
            new AttemptRef(
                seal.chainId(),
                seal.sequence(),
                seal.hash(),
                rolledBack ? AttemptOutcome.ROLLED_BACK : AttemptOutcome.UNKNOWN))
        .withPersistence(rolledBack ? "NOT_CONFIRMED" : "UNCERTAIN");
  }
}
