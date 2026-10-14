package pe.axiz.core.commons;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Standard paginated response body.
 *
 * @param items elements
 * @param page pagination metadata
 * @param nextCursor cursor for key-based pagination, or null
 * @param <T> element type
 */
public record PageResponse<T>(List<T> items, PageMetadata page, @Nullable String nextCursor) {

  public PageResponse {
    items = List.copyOf(items);
  }

  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(page.content(), page.metadata(), page.nextCursor());
  }
}
