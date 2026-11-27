package pe.axiz.core.audit;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Posición en la cadena y sello inmutables. La secuencia cero identifica solo la cabeza inicial.
 */
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

  /** Restaura la cabeza mínima almacenada; es estado para el cálculo, no el sello de un evento. */
  public static AuditIntegrity head(String chainId, long sequence, String hash) {
    return new AuditIntegrity(chainId, sequence, SealPreimage.GENESIS, hash, null, null);
  }

  /** Estado inicial previo a cualquier evento de una cadena. */
  public static AuditIntegrity initial(String chainId) {
    return head(chainId, 0, SealPreimage.GENESIS);
  }

  /** Impone la misma invariante de sucesor en memoria y en todo proveedor de persistencia. */
  public void requireSuccessor(AuditIntegrity next) {
    if (!chainId.equals(next.chainId())
        || next.sequence() != Math.addExact(sequence, 1)
        || !next.previousHash().equals(hash))
      throw new IllegalArgumentException("Invalid audit chain successor");
  }
}
