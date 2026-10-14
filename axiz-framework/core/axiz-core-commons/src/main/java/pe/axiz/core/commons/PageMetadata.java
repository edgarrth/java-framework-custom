package pe.axiz.core.commons;

/**
 * Pagination metadata returned with a page.
 *
 * @param page zero-based page index
 * @param size requested size
 * @param totalElements total elements, or -1 when unknown (cursor based stores)
 * @param totalPages total pages, or -1 when unknown
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
