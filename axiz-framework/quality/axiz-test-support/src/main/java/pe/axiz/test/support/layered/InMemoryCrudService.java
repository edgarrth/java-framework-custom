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
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.structure.layered.CrudService;

/**
 * {@link CrudService} respaldado por un mapa, exclusivo para pruebas. No aporta autorización ni
 * persistencia de producción.
 *
 * @param <T> tipo de recurso
 * @param <I> tipo de id (comparable, para un orden estable)
 */
public class InMemoryCrudService<T, I extends Comparable<I>> implements CrudService<T, T, I> {

  private final String resourceName;
  private final Function<T, I> idOf;
  private final BiFunction<I, T, T> withId;
  private final Function<T, String> versionOf;
  private final Map<I, T> items = new ConcurrentHashMap<>();

  /**
   * Crea el servicio.
   *
   * @param resourceName nombre que se usa en los errores
   * @param idOf extrae el id de un recurso
   * @param withId devuelve el recurso con el id indicado y la versión avanzada (para
   *     actualizaciones)
   * @param versionOf extrae la versión que se usa en las precondiciones de actualización y
   *     eliminación
   */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public InMemoryCrudService(
      String resourceName,
      Function<T, I> idOf,
      BiFunction<I, T, T> withId,
      Function<T, String> versionOf) {
    this.resourceName = Objects.requireNonNull(resourceName);
    this.idOf = Objects.requireNonNull(idOf);
    this.withId = Objects.requireNonNull(withId);
    this.versionOf = Objects.requireNonNull(versionOf);
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
          String expectedVersion = versionOf.apply(resource);
          if (expectedVersion == null || expectedVersion.isBlank())
            throw new ValidationException(
                List.of(FieldViolation.of("version", "Required", "Version is required")));
          if (!expectedVersion.equals(versionOf.apply(current)))
            throw new BusinessException(CoreErrors.CONFLICT, "Resource version conflict");
          return withId.apply(id, resource);
        });
  }

  @Override
  public void delete(I id, String expectedVersion) {
    items.compute(
        id,
        (key, current) -> {
          if (current == null) throw new NotFoundException(resourceName, id);
          if (expectedVersion == null || expectedVersion.isBlank())
            throw new ValidationException(
                List.of(FieldViolation.of("version", "Required", "Version is required")));
          if (!expectedVersion.equals(versionOf.apply(current)))
            throw new BusinessException(
                CoreErrors.PRECONDITION_FAILED, "Resource version precondition failed");
          return null;
        });
  }
}
