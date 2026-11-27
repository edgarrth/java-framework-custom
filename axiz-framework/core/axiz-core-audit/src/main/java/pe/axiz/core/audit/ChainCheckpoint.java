package pe.axiz.core.audit;

import java.time.Instant;
import java.util.Objects;

/** Cabeza confirmada obtenida de una fuente confiable; una métrica no es un checkpoint. */
public record ChainCheckpoint(String chainId, long sequence, String hash, Instant at) {
  public ChainCheckpoint {
    AuditIntegrity.validateChain(chainId);
    AuditIntegrity.validateHash(hash);
    if (sequence < 0)
      throw new IllegalArgumentException("Checkpoint sequence must not be negative");
    Objects.requireNonNull(at, "at");
  }
}
