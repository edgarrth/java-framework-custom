package pe.axiz.structure.layered.spring;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceUnitUtil;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.api.Sort.Direction;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.structure.layered.CrudAction;
import pe.axiz.structure.layered.CrudService;

/**
 * Authorized maintenance CRUD with separate entity, request and response types. An effective JPA
 * version mapping is mandatory. New entities are persisted, never merged; updates compare the
 * caller's version and keep JPA optimistic locking through commit. Subclasses must select allowed
 * fields, ownership and list scope. Domain commands for money movement belong outside this API.
 */
@Transactional
public abstract class JpaCrudService<E, RequestT, ResponseT, I>
    implements CrudService<RequestT, ResponseT, I> {
  private final JpaRepository<E, I> repository;
  private final JpaSpecificationExecutor<E> scoped;
  private final EntityManager entityManager;
  private final PersistenceUnitUtil persistence;
  private final String resourceName;

  protected JpaCrudService(
      Class<E> entityType,
      JpaRepository<E, I> repository,
      JpaSpecificationExecutor<E> scoped,
      EntityManager entityManager,
      String resourceName) {
    this.repository = Objects.requireNonNull(repository);
    this.scoped = Objects.requireNonNull(scoped);
    this.entityManager = Objects.requireNonNull(entityManager);
    this.persistence = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
    this.resourceName = Objects.requireNonNull(resourceName);
    if (!entityManager.getMetamodel().entity(entityType).hasVersionAttribute())
      throw new IllegalArgumentException("CRUD entities require an effective JPA version mapping");
  }

  /**
   * Creates a new entity with server-controlled identity and owner; never copies them from input.
   */
  protected abstract E newEntity(RequestT request);

  /** Copies only client-editable fields; never copies identity, owner or version. */
  protected abstract void apply(E current, RequestT changes);

  protected abstract ResponseT toResponse(E entity);

  /** Expected JPA version, with the same type as the entity version; null is rejected as input. */
  protected abstract @Nullable Object expectedVersion(RequestT request);

  /** Throws AccessException to deny; entity is null for CREATE and LIST. */
  protected abstract void authorize(CrudAction action, @Nullable E entity);

  /** Mandatory row restriction for the authenticated caller; returning null is an error. */
  protected abstract Specification<E> listScope();

  protected abstract Set<String> sortableFields();

  @Override
  public String resourceName() {
    return resourceName;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ResponseT> find(I id) {
    return repository.findById(id).filter(e -> allowed(CrudAction.READ, e)).map(this::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ResponseT> list(PageRequest request) {
    authorize(CrudAction.LIST, null);
    Set<String> allowed = Set.copyOf(sortableFields());
    for (var order : request.sort().orders()) {
      if (!allowed.contains(order.field()))
        throw new ValidationException(
            List.of(
                FieldViolation.of(
                    "sort", "NotSortable", "The requested sort field is not allowed")));
    }
    var page =
        scoped.findAll(Objects.requireNonNull(listScope(), "CRUD list scope"), toPageable(request));
    return Page.of(
        page.getContent().stream().map(this::toResponse).toList(),
        request,
        page.getTotalElements());
  }

  @Override
  public ResponseT create(RequestT request) {
    authorize(CrudAction.CREATE, null);
    E entity = Objects.requireNonNull(newEntity(request));
    entityManager.persist(entity);
    entityManager.flush();
    return toResponse(entity);
  }

  @Override
  public ResponseT update(I id, RequestT request) {
    E current = load(id, CrudAction.UPDATE);
    Object expected = expectedVersion(request);
    if (expected == null)
      throw new ValidationException(
          List.of(FieldViolation.of("version", "Required", "Expected version is required")));
    if (!expected.equals(persistence.getVersion(current)))
      throw new BusinessException(CoreErrors.CONFLICT, "Resource version conflict");
    entityManager.lock(current, LockModeType.OPTIMISTIC);
    apply(current, request);
    entityManager.flush();
    return toResponse(current);
  }

  @Override
  public void delete(I id) {
    E current = load(id, CrudAction.DELETE);
    entityManager.lock(current, LockModeType.OPTIMISTIC);
    repository.delete(current);
    entityManager.flush();
  }

  private E load(I id, CrudAction action) {
    E current = repository.findById(id).orElseThrow(() -> new NotFoundException(resourceName, id));
    if (!allowed(action, current)) throw new NotFoundException(resourceName, id);
    return current;
  }

  private boolean allowed(CrudAction action, E entity) {
    try {
      authorize(action, entity);
      return true;
    } catch (AccessException denied) {
      return false;
    }
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
