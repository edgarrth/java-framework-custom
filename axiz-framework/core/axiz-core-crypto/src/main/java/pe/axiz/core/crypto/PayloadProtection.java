package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;

/**
 * Formato de red versionado compartido; el parseo acotado nunca confía en el payload para su enlace
 * con la fila.
 */
final class PayloadProtection {
  static final int MAX_PLAIN = 16 * 1024 * 1024;
  private static final int MAX_STORED = MAX_PLAIN + 65_600;
  private static final byte[] MAGIC = {'A', 'X', 'Z', 'P'};
  private static final String PREFIX = "axzp1:";

  private PayloadProtection() {}

  static void binding(String context, String binding) {
    if (context.isBlank() || binding.isBlank())
      throw new CryptoException("Payload context and stable record binding are required");
    byte[] aad = EnvelopePayload.aad(context, binding);
    Arrays.fill(aad, (byte) 0);
  }

  static byte[] plaintext(byte[] bytes) {
    if (bytes.length > MAX_PLAIN) throw new CryptoException("Payload limit exceeded");
    return bytes;
  }

  static byte[] unprotected(byte[] bytes, String context, String binding) {
    binding(context, binding);
    plaintext(bytes);
    if (marked(bytes))
      throw new CryptoException("Protected payload cannot be read with protection disabled");
    return bytes.clone();
  }

  static String unprotected(String text, String context, String binding) {
    binding(context, binding);
    byte[] bytes = utf8(text);
    try {
      if (text.startsWith("axzp"))
        throw new CryptoException("Protected payload cannot be read with protection disabled");
      return text;
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  static byte[] frame(byte[] envelope) {
    if (envelope.length > MAX_STORED - 5)
      throw new CryptoException("Protected payload limit exceeded");
    return ByteBuffer.allocate(5 + envelope.length).put(MAGIC).put((byte) 1).put(envelope).array();
  }

  static byte[] unframe(byte[] stored) {
    if (stored.length < 6 || stored.length > MAX_STORED || !marked(stored) || stored[4] != 1)
      throw new CryptoException("Invalid protected payload version or length");
    return Arrays.copyOfRange(stored, 5, stored.length);
  }

  private static boolean marked(byte[] bytes) {
    return bytes.length >= MAGIC.length
        && Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
  }

  static String text(byte[] protectedBytes) {
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(protectedBytes);
  }

  static byte[] parse(String stored) {
    if (!stored.startsWith(PREFIX)
        || stored.length() > ((MAX_STORED + 2) / 3) * 4 + PREFIX.length())
      throw new CryptoException("Invalid protected payload text");
    String encoded = stored.substring(PREFIX.length());
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded))
        throw new CryptoException("Noncanonical protected payload text");
      return bytes;
    } catch (IllegalArgumentException invalid) {
      throw new CryptoException("Invalid protected payload text");
    }
  }

  static byte[] utf8(String text) {
    return StrictUtf8.encode(text, MAX_PLAIN);
  }

  static String utf8(byte[] bytes) {
    return StrictUtf8.decode(bytes, MAX_PLAIN);
  }
}
