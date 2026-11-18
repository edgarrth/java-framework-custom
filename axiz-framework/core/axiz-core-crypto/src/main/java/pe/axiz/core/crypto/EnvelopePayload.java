package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * One wire format for both stacks. Seal/open consume and clear their caller-owned raw-key buffer.
 */
final class EnvelopePayload {
  private final byte[] encryptedKey;
  private final byte[] ciphertext;

  private EnvelopePayload(byte[] encryptedKey, byte[] ciphertext) {
    this.encryptedKey = encryptedKey.clone();
    this.ciphertext = ciphertext.clone();
  }

  static void validatePolicy(Duration maxAge, long maxUses) {
    if (!maxAge.isPositive() || maxUses < 1)
      throw new IllegalArgumentException("Data-key age and uses must be positive");
  }

  static byte[] seal(byte[] plaintext, byte[] rawKey, byte[] encryptedKey, String context) {
    return seal(plaintext, rawKey, encryptedKey, context, null);
  }

  static byte[] seal(
      byte[] plaintext,
      byte[] rawKey,
      byte[] encryptedKey,
      String context,
      @Nullable String binding) {
    try {
      if (encryptedKey.length < 1 || encryptedKey.length > 0xFFFF)
        throw new CryptoException("Encrypted data key length is invalid");
      byte[] ct = AesGcm.encrypt(AesGcm.key(rawKey), plaintext, aad(context, binding));
      return ByteBuffer.allocate(3 + encryptedKey.length + ct.length)
          .put((byte) 1)
          .putShort((short) encryptedKey.length)
          .put(encryptedKey)
          .put(ct)
          .array();
    } finally {
      Arrays.fill(rawKey, (byte) 0);
    }
  }

  static EnvelopePayload parse(byte[] envelope) {
    if (envelope.length < 4) throw new CryptoException("Envelope too short");
    if (envelope[0] != 1) throw new CryptoException("Unsupported envelope version");
    int size = Short.toUnsignedInt(ByteBuffer.wrap(envelope, 1, 2).getShort());
    if (size == 0 || envelope.length < 3 + size + 28)
      throw new CryptoException("Malformed envelope");
    return new EnvelopePayload(
        Arrays.copyOfRange(envelope, 3, 3 + size),
        Arrays.copyOfRange(envelope, 3 + size, envelope.length));
  }

  byte[] encryptedKey() {
    return encryptedKey.clone();
  }

  byte[] open(byte[] rawKey, String context) {
    return open(rawKey, context, null);
  }

  static byte[] aad(String context, @Nullable String binding) {
    if (context.isBlank()) throw new CryptoException("Envelope context is required");
    return binding == null ? Aad.of(Aad.ENVELOPE, context) : Aad.of(Aad.ENVELOPE, context, binding);
  }

  byte[] open(byte[] rawKey, String context, @Nullable String binding) {
    try {
      return AesGcm.decrypt(AesGcm.key(rawKey), ciphertext, aad(context, binding));
    } finally {
      Arrays.fill(rawKey, (byte) 0);
    }
  }
}
