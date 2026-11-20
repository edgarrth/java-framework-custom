package pe.axiz.core.tokenization.vault;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import pe.axiz.core.tokenization.SensitiveValue;

/** Owned UTF-8 boundary buffers; malformed authenticated data still cannot become a value. */
final class VaultText {
  private VaultText() {}

  static byte[] encode(SensitiveValue value) {
    return value.use(
        chars -> {
          byte[] buffer = new byte[SensitiveValue.MAX_UTF8_BYTES];
          try {
            ByteBuffer bytes = ByteBuffer.wrap(buffer);
            var encoder =
                StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT);
            var result = encoder.encode(CharBuffer.wrap(chars), bytes, true);
            if (result.isError() || result.isOverflow()) throw new VaultIntegrityException();
            if (encoder.flush(bytes).isError()) throw new VaultIntegrityException();
            return Arrays.copyOf(buffer, bytes.position());
          } finally {
            Arrays.fill(buffer, (byte) 0);
          }
        });
  }

  static SensitiveValue decode(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > SensitiveValue.MAX_UTF8_BYTES)
      throw new VaultIntegrityException();
    char[] buffer = new char[bytes.length];
    char[] result = null;
    try {
      var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
      CharBuffer chars = CharBuffer.wrap(buffer);
      var outcome = decoder.decode(ByteBuffer.wrap(bytes), chars, true);
      if (outcome.isError() || outcome.isOverflow()) throw new VaultIntegrityException();
      if (decoder.flush(chars).isError()) throw new VaultIntegrityException();
      result = Arrays.copyOf(buffer, chars.position());
      return SensitiveValue.of(result);
    } finally {
      Arrays.fill(buffer, '\0');
      if (result != null) Arrays.fill(result, '\0');
    }
  }

  static boolean matches(SensitiveValue expected, byte[] actual) {
    byte[] encoded = encode(expected);
    try {
      return MessageDigest.isEqual(encoded, actual);
    } finally {
      Arrays.fill(encoded, (byte) 0);
    }
  }
}
