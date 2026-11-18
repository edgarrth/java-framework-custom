package pe.axiz.core.tokenization.vault;

import java.util.Objects;

/**
 * Immutable transition issued only by the maintenance protocol, after its preconditions. This
 * object is not an authorization credential; adapters require a separately privileged client.
 */
public final class VaultKeyTransition {
  private final FingerprintKeyState expected;
  private final FingerprintKeyState next;

  VaultKeyTransition(FingerprintKeyState expected, FingerprintKeyState next) {
    this.expected = Objects.requireNonNull(expected);
    this.next = Objects.requireNonNull(next);
    if (!expected.keysetId().equals(next.keysetId())
        || next.version() != Math.addExact(expected.version(), 1))
      throw new IllegalArgumentException("Invalid vault key transition");
  }

  public FingerprintKeyState expected() {
    return expected;
  }

  public FingerprintKeyState next() {
    return next;
  }
}
