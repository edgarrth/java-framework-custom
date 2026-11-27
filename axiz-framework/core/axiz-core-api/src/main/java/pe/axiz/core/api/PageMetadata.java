package pe.axiz.core.api;

/**
 * Metadatos de paginación devueltos con una página.
 *
 * @param page índice de página desde cero
 * @param size tamaño solicitado
 * @param totalElements total de elementos, o -1 cuando se desconoce (almacenes basados en cursor)
 * @param totalPages total de páginas, o -1 cuando se desconoce
 */
public record PageMetadata(int page, int size, long totalElements, int totalPages) {

  public static PageMetadata of(PageRequest request, long totalElements) {
    int pages =
        totalElements < 0 ? -1 : (int) ((totalElements + request.size() - 1) / request.size());
    return new PageMetadata(request.page(), request.size(), totalElements, pages);
  }

  public boolean hasNext() {
    return totalPages < 0 || page + 1 < totalPages;
  }
}
