package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Contrato de red fijo: AXIZ-INDEX, versión, cantidad y luego ocho partes UTF-8 delimitadas por
 * longitud.
 */
final class IndexPreimage {
  private static final byte[] MAGIC = "AXIZ-INDEX".getBytes(StandardCharsets.US_ASCII);

  private IndexPreimage() {}

  static byte[] of(CharSequence canonicalValue, IndexScope scope, String keyId) {
    CharSequence[] parts = {
      scope.purpose(),
      scope.fieldType(),
      scope.tenant(),
      scope.domain(),
      scope.profileId(),
      Integer.toString(scope.canonicalVersion()),
      keyId,
      canonicalValue
    };
    byte[][] encoded = new byte[parts.length][];
    int size = MAGIC.length + 2;
    try {
      for (int i = 0; i < parts.length; i++) {
        encoded[i] = StrictUtf8.encode(parts[i], i == 7 ? 8192 : 1024);
        size += 4 + encoded[i].length;
      }
      ByteBuffer out = ByteBuffer.allocate(size).put(MAGIC).put((byte) 1).put((byte) parts.length);
      for (byte[] part : encoded) out.putInt(part.length).put(part);
      return out.array();
    } finally {
      for (byte[] part : encoded) if (part != null) Arrays.fill(part, (byte) 0);
    }
  }
}
