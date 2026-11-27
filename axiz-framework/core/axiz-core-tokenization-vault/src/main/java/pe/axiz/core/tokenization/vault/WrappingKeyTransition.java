package pe.axiz.core.tokenization.vault;

import java.util.Objects;

/**
 * Transición inmutable de clave de envoltura emitida tras las verificaciones del protocolo
 * privilegiado, no una credencial IAM.
 */
public final class WrappingKeyTransition {
  private final WrappingKeyState expected;
  private final WrappingKeyState next;

  WrappingKeyTransition(WrappingKeyState expected, WrappingKeyState next) {
    this.expected = Objects.requireNonNull(expected);
    this.next = Objects.requireNonNull(next);
    if (!expected.keysetId().equals(next.keysetId())
        || next.version() != Math.addExact(expected.version(), 1))
      throw new IllegalArgumentException("Invalid wrapping-key transition");
  }

  public WrappingKeyState expected() {
    return expected;
  }

  public WrappingKeyState next() {
    return next;
  }
}
