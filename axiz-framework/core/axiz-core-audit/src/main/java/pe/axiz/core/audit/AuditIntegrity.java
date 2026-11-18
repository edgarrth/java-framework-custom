package pe.axiz.core.audit;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable chain position and seal. Sequence zero identifies the initial head only. */
public record AuditIntegrity(
    String chainId,
    long sequence,
    String previousHash,
    String hash,
    @Nullable String keyId,
    @Nullable AttemptRef previousAttempt) {
  public AuditIntegrity {
    validateChain(chainId);
    validateHash(previousHash);
    validateHash(hash);
    if (sequence < 0) throw new IllegalArgumentException("Chain sequence must not be negative");
    if (keyId != null && !keyId.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid audit key identifier");
  }

  static void validateChain(String chainId) {
    Objects.requireNonNull(chainId, "chainId");
    if (!chainId.matches("[A-Za-z0-9._:/-]{1,256}"))
      throw new IllegalArgumentException("Invalid audit chain identifier");
  }

  static void validateHash(String hash) {
    Objects.requireNonNull(hash, "hash");
    if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid audit hash");
  }

  /** Restores the minimal stored head; it is state for calculation, not an event seal. */
  public static AuditIntegrity head(String chainId, long sequence, String hash) {
    return new AuditIntegrity(chainId, sequence, SealPreimage.GENESIS, hash, null, null);
  }

  /** Initial state before any event in a chain. */
  public static AuditIntegrity initial(String chainId) {
    return head(chainId, 0, SealPreimage.GENESIS);
  }

  /** Enforces the same successor invariant in memory and in every persistence provider. */
  public void requireSuccessor(AuditIntegrity next) {
    if (!chainId.equals(next.chainId())
        || next.sequence() != Math.addExact(sequence, 1)
        || !next.previousHash().equals(hash))
      throw new IllegalArgumentException("Invalid audit chain successor");
  }
}
