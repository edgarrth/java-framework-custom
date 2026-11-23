package pe.axiz.core.storage;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Metadatos de un objeto almacenado.
 *
 * @param contentType tipo MIME
 * @param contentLength tamaño en bytes, o -1 cuando se desconoce
 * @param checksumSha256 SHA-256 en base64 cuando se conoce
 * @param userMetadata metadatos personalizados (sin datos personales)
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
