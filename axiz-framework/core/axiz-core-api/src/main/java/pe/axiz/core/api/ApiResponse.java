package pe.axiz.core.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Optional response envelope for services whose consumers require one. Plain bodies are the
 * default; errors always use RFC 9457 problem details, never this envelope.
 *
 * @param data payload
 * @param meta metadata such as correlation id
 * @param <T> payload type
 */
public record ApiResponse<T>(@Nullable T data, Map<String, Object> meta) {

  public ApiResponse {
    meta = Map.copyOf(meta);
  }

  public static <T> ApiResponse<T> of(T data) {
    return new ApiResponse<>(data, Map.of());
  }

  public static <T> ApiResponse<T> of(T data, String correlationId) {
    return new ApiResponse<>(data, Map.of("correlationId", correlationId));
  }
}
