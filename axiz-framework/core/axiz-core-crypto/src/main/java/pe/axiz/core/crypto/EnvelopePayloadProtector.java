package pe.axiz.core.crypto;

import java.util.Objects;

/**
 * AES-GCM payload protection delegates key management and authenticated binding to the envelope.
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
