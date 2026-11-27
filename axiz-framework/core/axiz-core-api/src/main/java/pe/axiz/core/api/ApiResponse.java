package pe.axiz.core.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Envelope de respuesta opcional para servicios cuyos consumidores lo requieren. Los cuerpos
 * simples son el valor por defecto; los errores siempre usan problem details RFC 9457, nunca este
 * envelope.
 *
 * @param data payload
 * @param meta metadatos como el id de correlación
 * @param <T> tipo del payload
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
