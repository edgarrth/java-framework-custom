package pe.axiz.core.storage;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Metadata of a stored object.
 *
 * @param contentType MIME type
 * @param contentLength size in bytes, or -1 when unknown
 * @param checksumSha256 base64 SHA-256 when known
 * @param userMetadata custom metadata (no personal data)
 */
public record ObjectMetadata(
    String contentType,
    long contentLength,
    @Nullable String checksumSha256,
    Map<String, String> userMetadata) {

  public ObjectMetadata {
    Objects.requireNonNull(contentType);
    userMetadata = Map.copyOf(userMetadata);
  }

  public static ObjectMetadata of(String contentType, long length) {
    return new ObjectMetadata(contentType, length, null, Map.of());
  }
}
