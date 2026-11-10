package pe.axiz.structure.layered.spring;

import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.commons.Sort.Direction;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.structure.layered.CrudService;

/**
 * {@link CrudService} over a Spring Data JPA repository. Reads run in read-only transactions
 * (routed to Aurora readers when read/write splitting is enabled).
 *
 * @param <T> entity type
 * @param <I> id type
 */
@Transactional
public abstract class JpaCrudService<T, I> implements CrudService<T, I> {

  private final JpaRepository<T, I> repository;
  private final String resourceName;

  protected JpaCrudService(JpaRepository<T, I> repository, String resourceName) {
    this.repository = repository;
    this.resourceName = resourceName;
  }

  /** Copies the updatable fields of {@code changes} onto the managed {@code current} entity. */
  protected abstract void apply(T current, T changes);

  @Override
  public String resourceName() {
    return resourceName;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<T> find(I id) {
    return repository.findById(id);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<T> list(PageRequest request) {
    org.springframework.data.domain.Page<T> page = repository.findAll(toPageable(request));
    return Page.of(page.getContent(), request, page.getTotalElements());
  }

  @Override
  public T create(T resource) {
    return repository.save(resource);
  }

  @Override
  public T update(I id, T resource) {
    T current = repository.findById(id).orElseThrow(() -> new NotFoundException(resourceName, id));
    apply(current, resource);
    return repository.save(current);
  }

  @Override
  public void delete(I id) {
    T current = repository.findById(id).orElseThrow(() -> new NotFoundException(resourceName, id));
    repository.delete(current);
  }

  static org.springframework.data.domain.PageRequest toPageable(PageRequest request) {
    Sort sort =
        Sort.by(
            request.sort().orders().stream()
                .map(
                    o ->
                        o.direction() == Direction.DESC
                            ? Sort.Order.desc(o.field())
                            : Sort.Order.asc(o.field()))
                .toList());
    return org.springframework.data.domain.PageRequest.of(request.page(), request.size(), sort);
  }
}
