package pe.axiz.framework.layered;

/**
 * Write operations of a resource. Implementations are transactional.
 *
 * @param <T> resource type
 * @param <I> id type
 */
public interface CommandService<T, I> {

  T create(T resource);

  /** Replaces the resource; a missing id is a 404 problem. */
  T update(I id, T resource);

  /** Deletes the resource; deleting a missing id is a 404 problem. */
  void delete(I id);
}
