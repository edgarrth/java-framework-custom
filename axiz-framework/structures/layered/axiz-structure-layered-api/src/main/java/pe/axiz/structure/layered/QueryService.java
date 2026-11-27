package pe.axiz.structure.layered;

import java.util.Optional;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.NotFoundException;

/**
 * Operaciones de lectura de un recurso.
 *
 * @param <T> tipo del recurso
 * @param <I> tipo del id
 */
public interface QueryService<T, I> {

  /** Nombre lógico del recurso usado en los errores, por ejemplo {@code parameter}. */
  String resourceName();

  Optional<T> find(I id);

  Page<T> list(PageRequest request);

  /** El recurso o un problema 404. */
  default T get(I id) {
    return find(id).orElseThrow(() -> new NotFoundException(resourceName(), id));
  }
}
