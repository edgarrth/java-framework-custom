package pe.axiz.core.api;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.lang.Checks;

/**
 * Página de resultados.
 *
 * @param content elementos de esta página
 * @param metadata metadatos de paginación
 * @param nextCursor cursor opaco para paginación por clave (DynamoDB), o null
 * @param <T> tipo de elemento
 */
public record Page<T>(List<T> content, PageMetadata metadata, @Nullable String nextCursor) {

  public Page {
    content = List.copyOf(content);
    Checks.notNull(metadata, "metadata");
  }

  public static <T> Page<T> of(List<T> content, PageRequest request, long totalElements) {
    return new Page<>(content, PageMetadata.of(request, totalElements), null);
  }

  public static <T> Page<T> empty(PageRequest request) {
    return of(List.of(), request, 0);
  }

  public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
    return new Page<>(content.stream().<R>map(mapper).toList(), metadata, nextCursor);
  }
}
