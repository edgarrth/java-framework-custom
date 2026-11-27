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
 * CRUD de mantenimiento autorizado con tipos de entidad, petición y respuesta separados. Es
 * obligatorio un mapeo de versión JPA efectivo. Las entidades nuevas se persisten, nunca se
 * fusionan; las actualizaciones comparan la versión de quien llama y mantienen el bloqueo optimista
 * de JPA hasta el commit. Las subclases deben seleccionar los campos permitidos, la propiedad y el
 * alcance de los listados. Los comandos de dominio para movimientos de dinero quedan fuera de esta
 * API.
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
    Objects.requireNonNull(repository);
    Objects.requireNonNull(scoped);
    Objects.requireNonNull(entityManager);
    Objects.requireNonNull(resourceName);
    PersistenceUnitUtil unit = validatedPersistence(entityType, entityManager);
    super();
    this.repository = repository;
    this.scoped = scoped;
    this.entityManager = entityManager;
    this.persistence = unit;
    this.resourceName = resourceName;
  }

  private static <E> PersistenceUnitUtil validatedPersistence(
      Class<E> entityType, EntityManager entityManager) {
    if (!entityManager.getMetamodel().entity(entityType).hasVersionAttribute())
      throw new IllegalArgumentException("CRUD entities require an effective JPA version mapping");
    return entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
  }

  /**
   * Crea una entidad nueva con identidad y propietario controlados por el servidor; nunca los copia
   * de la entrada.
   */
  protected abstract E newEntity(RequestT request);

  /**
   * Copia solo los campos editables por el cliente; nunca copia la identidad, el propietario ni la
   * versión.
   */
  protected abstract void apply(E current, RequestT changes);

  protected abstract ResponseT toResponse(E entity);

  /**
   * Versión JPA esperada, del mismo tipo que la versión de la entidad; null se rechaza como
   * entrada.
   */
  protected abstract @Nullable Object expectedVersion(RequestT request);

  /** Lanza AccessException para denegar; la entidad es null para CREATE y LIST. */
  protected abstract void authorize(CrudAction action, @Nullable E entity);

  /** Restricción de filas obligatoria para el llamador autenticado; devolver null es un error. */
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
  public void delete(I id, String expectedVersion) {
    E current = load(id, CrudAction.DELETE);
    if (expectedVersion == null || expectedVersion.isBlank())
      throw new ValidationException(
          List.of(FieldViolation.of("version", "Required", "Expected version is required")));
    if (!expectedVersion.equals(String.valueOf(persistence.getVersion(current))))
      throw new BusinessException(
          CoreErrors.PRECONDITION_FAILED, "Resource version precondition failed");
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
