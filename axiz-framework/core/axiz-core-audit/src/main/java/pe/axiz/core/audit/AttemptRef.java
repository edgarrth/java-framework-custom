package pe.axiz.core.audit;

import java.util.Objects;

/** A previous attempt of the same fact; its full identity participates in the new seal. */
public record AttemptRef(String chainId, long sequence, String hash, AttemptOutcome outcome) {
  public AttemptRef {
    AuditIntegrity.validateChain(chainId);
    AuditIntegrity.validateHash(hash);
    if (sequence < 1) throw new IllegalArgumentException("Attempt sequence must be positive");
    Objects.requireNonNull(outcome, "outcome");
  }
}
