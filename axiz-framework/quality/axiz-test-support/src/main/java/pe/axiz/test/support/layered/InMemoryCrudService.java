package pe.axiz.test.support.layered;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.structure.layered.CrudService;

/**
 * Map-backed {@link CrudService} for tests only. Does not provide production authorization or
 * persistence.
 *
 * @param <T> resource type
 * @param <I> id type (comparable, for a stable order)
 */
public class InMemoryCrudService<T, I extends Comparable<I>> implements CrudService<T, T, I> {

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
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
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
    if (items.putIfAbsent(idOf.apply(resource), resource) != null)
      throw new IllegalStateException("Duplicate fixture identifier");
    return resource;
  }

  @Override
  public T update(I id, T resource) {
    return items.compute(
        id,
        (key, current) -> {
          if (current == null) throw new NotFoundException(resourceName, id);
          return withId.apply(id, resource);
        });
  }

  @Override
  public void delete(I id) {
    if (items.remove(id) == null) {
      throw new NotFoundException(resourceName, id);
    }
  }
}
