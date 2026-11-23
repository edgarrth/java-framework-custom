package pe.axiz.core.crypto;

import java.util.Objects;

/**
 * La protección de payload AES-GCM delega la gestión de claves y el enlace autenticado en el
 * envelope.
 */
public final class EnvelopePayloadProtector implements PayloadProtector {
  private final EnvelopeEncryptor envelope;

  public EnvelopePayloadProtector(EnvelopeEncryptor envelope) {
    this.envelope = Objects.requireNonNull(envelope);
  }

  @Override
  public byte[] protect(byte[] plaintext, String context, String binding) {
    PayloadProtection.binding(context, binding);
    return PayloadProtection.frame(
        envelope.encrypt(PayloadProtection.plaintext(plaintext), context, binding));
  }

  @Override
  public byte[] reveal(byte[] stored, String context, String binding) {
    PayloadProtection.binding(context, binding);
    return envelope.decrypt(PayloadProtection.unframe(stored), context, binding);
  }
}
