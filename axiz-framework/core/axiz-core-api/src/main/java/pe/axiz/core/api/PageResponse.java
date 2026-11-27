package pe.axiz.core.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Cuerpo de respuesta paginada estándar.
 *
 * @param items elementos
 * @param page metadatos de paginación
 * @param nextCursor cursor para paginación por clave, o null
 * @param <T> tipo de elemento
 */
public record PageResponse<T>(List<T> items, PageMetadata page, @Nullable String nextCursor) {

  public PageResponse {
    items = List.copyOf(items);
  }

  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(page.content(), page.metadata(), page.nextCursor());
  }
}
