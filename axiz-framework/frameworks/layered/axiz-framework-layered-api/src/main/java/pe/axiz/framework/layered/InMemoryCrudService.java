package pe.axiz.framework.layered;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.errors.NotFoundException;

/**
 * Map-backed {@link CrudService} for tests, prototypes and the local profile.
 *
 * @param <T> resource type
 * @param <I> id type (comparable, for a stable order)
 */
public class InMemoryCrudService<T, I extends Comparable<I>> implements CrudService<T, I> {

  private final String resourceName;
  private final Function<T, I> idOf;
  private final BiFunction<I, T, T> withId;
  private final Map<I, T> items = new ConcurrentHashMap<>();

  /**
   * Creates the service.
   *
   * @param resourceName name used in errors
   * @param idOf extracts the id of a resource
   * @param withId returns the resource with the given id (for updates)
   */
  public InMemoryCrudService(String resourceName, Function<T, I> idOf, BiFunction<I, T, T> withId) {
    this.resourceName = Objects.requireNonNull(resourceName);
    this.idOf = Objects.requireNonNull(idOf);
    this.withId = Objects.requireNonNull(withId);
  }

  @Override
  public String resourceName() {
    return resourceName;
  }

  @Override
  public Optional<T> find(I id) {
    return Optional.ofNullable(items.get(id));
  }

  @Override
  public Page<T> list(PageRequest request) {
    List<I> ids = new ArrayList<>(items.keySet());
    ids.sort(Comparator.naturalOrder());
    int from = (int) Math.min(request.offset(), ids.size());
    int to = Math.min(from + request.size(), ids.size());
    return Page.of(ids.subList(from, to).stream().map(items::get).toList(), request, ids.size());
  }

  @Override
  public T create(T resource) {
    items.put(idOf.apply(resource), resource);
    return resource;
  }

  @Override
  public T update(I id, T resource) {
    if (!items.containsKey(id)) {
      throw new NotFoundException(resourceName, id);
    }
    T stored = withId.apply(id, resource);
    items.put(id, stored);
    return stored;
  }

  @Override
  public void delete(I id) {
    if (items.remove(id) == null) {
      throw new NotFoundException(resourceName, id);
    }
  }
}
