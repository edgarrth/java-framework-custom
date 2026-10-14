package pe.axiz.framework.layered;

import java.util.Optional;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.errors.NotFoundException;

/**
 * Read operations of a resource.
 *
 * @param <T> resource type
 * @param <I> id type
 */
public interface QueryService<T, I> {

  /** Logical resource name used in errors, e.g. {@code parameter}. */
  String resourceName();

  Optional<T> find(I id);

  Page<T> list(PageRequest request);

  /** The resource or a 404 problem. */
  default T get(I id) {
    return find(id).orElseThrow(() -> new NotFoundException(resourceName(), id));
  }
}
