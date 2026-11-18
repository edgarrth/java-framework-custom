package pe.axiz.core.audit;

import java.time.Instant;
import java.util.Objects;

/** A committed head obtained from a trusted source; a metric is not a checkpoint. */
public record ChainCheckpoint(String chainId, long sequence, String hash, Instant at) {
  public ChainCheckpoint {
    AuditIntegrity.validateChain(chainId);
    AuditIntegrity.validateHash(hash);
    if (sequence < 0)
      throw new IllegalArgumentException("Checkpoint sequence must not be negative");
    Objects.requireNonNull(at, "at");
  }
}
