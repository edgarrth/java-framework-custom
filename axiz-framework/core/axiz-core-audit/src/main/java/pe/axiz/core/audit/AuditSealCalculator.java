package pe.axiz.core.audit;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Cálculo de sellos puro y thread-safe, sin estado de cadena ni E/S. */
public final class AuditSealCalculator {
  private final AuditSealer.Mode mode;
  private final @Nullable Supplier<byte[]> hmacKey;
  private final @Nullable String keyId;

  /**
   * El proveedor de HMAC transfiere un buffer nuevo en cada llamada; el calculador borra ese
   * buffer.
   */
  public AuditSealCalculator(
      AuditSealer.Mode mode, @Nullable Supplier<byte[]> hmacKey, @Nullable String keyId) {
    this.mode = Objects.requireNonNull(mode);
    if (mode == AuditSealer.Mode.HMAC) {
      Objects.requireNonNull(hmacKey, "hmacKey");
      if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}"))
        throw new IllegalArgumentException("HMAC requires an audit key identifier");
    } else if (hmacKey != null || keyId != null) {
      throw new IllegalArgumentException("Audit keys apply only to HMAC mode");
    }
    this.hmacKey = hmacKey;
    this.keyId = keyId;
    // Se resuelven los proveedores JCA durante la composición, antes de usarlos en un hilo del
    // event loop.
    SealPreimage.prepare(mode);
  }

  public boolean enabled() {
    return mode != AuditSealer.Mode.NONE;
  }

  public AuditIntegrity next(
      AuditEvent unsealed, AuditIntegrity head, @Nullable AttemptRef previousAttempt) {
    if (mode == AuditSealer.Mode.NONE)
      throw new IllegalStateException("Seal calculation is disabled");
    if (unsealed.integrity() != null) throw new IllegalStateException("An event is already sealed");
    var position =
        new AuditIntegrity(
            head.chainId(),
            Math.addExact(head.sequence(), 1),
            head.hash(),
            SealPreimage.GENESIS,
            keyId,
            previousAttempt);
    byte[] key = hmacKey == null ? null : Objects.requireNonNull(hmacKey.get(), "hmacKey result");
    try {
      String hash = SealPreimage.hash(unsealed, position, key);
      return new AuditIntegrity(
          position.chainId(),
          position.sequence(),
          position.previousHash(),
          hash,
          keyId,
          previousAttempt);
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
    }
  }
}
