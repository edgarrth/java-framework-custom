package pe.axiz.adapter.secrets.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import pe.axiz.core.secrets.SecretValue;

/**
 * Lector UTF-8 acotado único para secretos montados, tanto bajo demanda de peticiones como
 * renovados.
 */
final class SecretFileContents {
  static final int MAX_BYTES = 1024 * 1024;

  private SecretFileContents() {}

  static Contents read(Path directory, Path file) throws IOException {
    Path root = directory.toRealPath();
    Path target = file.toRealPath();
    if (!target.startsWith(root))
      throw new IllegalStateException("Mounted secret link leaves its directory");
    byte[] bytes;
    try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(MAX_BYTES + 1);
    }
    try {
      if (bytes.length > MAX_BYTES)
        throw new IllegalStateException("Mounted secret exceeds byte limit");
      CharBuffer decoded = CharBuffer.allocate(bytes.length);
      try {
        var decoder = StandardCharsets.UTF_8.newDecoder();
        var result = decoder.decode(ByteBuffer.wrap(bytes), decoded, true);
        if (result.isError()) result.throwException();
        if (result.isOverflow())
          throw new IllegalStateException("Mounted secret decoding exceeds limit");
        var flushed = decoder.flush(decoded);
        if (flushed.isError()) flushed.throwException();
        decoded.flip();
        int start = 0;
        int end = decoded.limit();
        while (start < end && Character.isWhitespace(Character.codePointAt(decoded, start)))
          start += Character.charCount(Character.codePointAt(decoded, start));
        while (start < end && Character.isWhitespace(Character.codePointBefore(decoded, end)))
          end -= Character.charCount(Character.codePointBefore(decoded, end));
        if (start == end) return new Contents(bytes.length, Optional.empty());
        char[] value = new char[end - start];
        try {
          decoded.position(start);
          decoded.get(value);
          return new Contents(bytes.length, Optional.of(SecretValue.of(value)));
        } finally {
          Arrays.fill(value, '\0');
        }
      } finally {
        if (decoded.hasArray())
          Arrays.fill(
              decoded.array(),
              decoded.arrayOffset(),
              decoded.arrayOffset() + decoded.capacity(),
              '\0');
      }
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  record Contents(int bytes, Optional<SecretValue> value) {}
}
