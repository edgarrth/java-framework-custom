package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/** One wire format for both synchronous and native asynchronous envelope encryption. */
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
    if (encryptedKey.length < 1 || encryptedKey.length > 0xFFFF)
      throw new CryptoException("Encrypted data key length is invalid");
    byte[] ct =
        AesGcm.encrypt(AesGcm.key(rawKey), plaintext, context.getBytes(StandardCharsets.UTF_8));
    return ByteBuffer.allocate(2 + encryptedKey.length + ct.length)
        .putShort((short) encryptedKey.length)
        .put(encryptedKey)
        .put(ct)
        .array();
  }

  static EnvelopePayload parse(byte[] envelope) {
    if (envelope.length < 3) throw new CryptoException("Envelope too short");
    int size = Short.toUnsignedInt(ByteBuffer.wrap(envelope).getShort());
    if (size == 0 || envelope.length < 2 + size + 28)
      throw new CryptoException("Malformed envelope");
    return new EnvelopePayload(
        Arrays.copyOfRange(envelope, 2, 2 + size),
        Arrays.copyOfRange(envelope, 2 + size, envelope.length));
  }

  byte[] encryptedKey() {
    return encryptedKey.clone();
  }

  byte[] open(byte[] rawKey, String context) {
    return AesGcm.decrypt(AesGcm.key(rawKey), ciphertext, context.getBytes(StandardCharsets.UTF_8));
  }
}
