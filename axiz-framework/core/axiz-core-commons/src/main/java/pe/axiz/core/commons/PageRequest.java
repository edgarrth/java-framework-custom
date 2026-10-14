package pe.axiz.core.commons;

/**
 * Page request with zero-based page index.
 *
 * @param page zero-based page index
 * @param size page size, bounded by {@link #MAX_SIZE}
 * @param sort sort specification
 */
public record PageRequest(int page, int size, Sort sort) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 500;

  public PageRequest {
    Checks.notNegative(page, "page");
    Checks.positive(size, "size");
    Checks.argument(size <= MAX_SIZE, "size must be at most " + MAX_SIZE);
    Checks.notNull(sort, "sort");
  }

  public static PageRequest of(int page, int size) {
    return new PageRequest(page, size, Sort.unsorted());
  }

  public static PageRequest first() {
    return of(0, DEFAULT_SIZE);
  }

  public long offset() {
    return (long) page * size;
  }

  public PageRequest next() {
    return new PageRequest(page + 1, size, sort);
  }
}
