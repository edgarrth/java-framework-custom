package pe.axiz.core.audit;

import java.util.Objects;

/** Intento previo del mismo hecho; su identidad completa participa en el nuevo sello. */
public record AttemptRef(String chainId, long sequence, String hash, AttemptOutcome outcome) {
  public AttemptRef {
    AuditIntegrity.validateChain(chainId);
    AuditIntegrity.validateHash(hash);
    if (sequence < 1) throw new IllegalArgumentException("Attempt sequence must be positive");
    Objects.requireNonNull(outcome, "outcome");
  }
}
