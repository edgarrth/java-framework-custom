package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded, lossless text encoding for authenticated cryptographic inputs. */
final class StrictUtf8 {
  private StrictUtf8() {}

  static byte[] encode(String value, int maximum) {
    if (value == null || value.length() > maximum) throw new CryptoException("Invalid text length");
    try {
      ByteBuffer buffer =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      try {
        if (buffer.remaining() > maximum) throw new CryptoException("Encoded text exceeds limit");
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
      } finally {
        if (buffer.hasArray())
          Arrays.fill(
              buffer.array(),
              buffer.arrayOffset(),
              buffer.arrayOffset() + buffer.capacity(),
              (byte) 0);
      }
    } catch (CharacterCodingException error) {
      throw new CryptoException("Text is not well formed", error);
    }
  }

  static String decode(byte[] value, int maximum) {
    if (value.length > maximum) throw new CryptoException("Encoded text exceeds limit");
    try {
      CharBuffer decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(value));
      try {
        return decoded.toString();
      } finally {
        if (decoded.hasArray())
          Arrays.fill(
              decoded.array(),
              decoded.arrayOffset(),
              decoded.arrayOffset() + decoded.capacity(),
              '\0');
      }
    } catch (CharacterCodingException malformed) {
      throw new CryptoException("Text is not well formed");
    }
  }
}
