package pe.axiz.core.crypto;

import java.nio.ByteBuffer;

/** Versioned, domain-separated, length-framed authenticated data shared by all encryptors. */
final class Aad {
  static final byte FORMAT = 1;
  static final byte FIELD = 1;
  static final byte ENVELOPE = 2;
  static final byte PAYLOAD = 3;
  static final int MAX_PARTS = 8;
  static final int MAX_PART_BYTES = 1024;

  private Aad() {}

  static byte[] of(byte usage, String... parts) {
    if (parts.length == 0 || parts.length > MAX_PARTS)
      throw new CryptoException("Invalid authenticated data parts");
    byte[][] encoded = new byte[parts.length][];
    int size = 3;
    for (int i = 0; i < parts.length; i++) {
      encoded[i] = StrictUtf8.encode(parts[i], MAX_PART_BYTES);
      size += 4 + encoded[i].length;
    }
    ByteBuffer out = ByteBuffer.allocate(size).put(FORMAT).put(usage).put((byte) parts.length);
    for (byte[] part : encoded) out.putInt(part.length).put(part);
    return out.array();
  }
}
