package pe.axiz.structure.layered.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.api.Sort;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.structure.layered.CrudAction;

/** Actual Hibernate persistence, object ownership, request allowlists and optimistic conflicts. */
class CrudLayersTest {
  @Entity(name = "MaintenanceBranch")
  @Table(name = "maintenance_branch")
  public static class Branch {
    @Id String id;
    @Version Long version;
    String owner;
    String name;

    protected Branch() {}

    Branch(String owner, String name) {
      this.id = UUID.randomUUID().toString();
      this.owner = owner;
      this.name = name;
    }
  }

  @Entity(name = "UnversionedBranch")
  public static class Unversioned {
    @Id String id;
  }

  record Change(String name, Long version) {}

  record View(String id, String name, Long version) {}

  static final class Branches extends JpaCrudService<Branch, Change, View, String> {
    private final String caller;

    Branches(EntityManager em, String caller) {
      this(em, new SimpleJpaRepository<>(Branch.class, em), caller);
    }

    private Branches(EntityManager em, SimpleJpaRepository<Branch, String> repo, String caller) {
      super(Branch.class, repo, repo, em, "branch");
      this.caller = caller;
    }

    protected Branch newEntity(Change request) {
      return new Branch(caller, request.name());
    }

    protected void apply(Branch current, Change request) {
      current.name = request.name();
    }

    protected View toResponse(Branch entity) {
      return new View(entity.id, entity.name, entity.version);
    }

    protected Object expectedVersion(Change request) {
      return request.version();
    }

    protected void authorize(CrudAction action, @Nullable Branch entity) {
      if (caller.isBlank() || (entity != null && !caller.equals(entity.owner)))
        throw AccessException.forbidden("Caller cannot access this maintenance resource");
    }

    protected Specification<Branch> listScope() {
      return (root, query, builder) -> builder.equal(root.get("owner"), caller);
    }

    protected Set<String> sortableFields() {
      return Set.of("name");
    }
  }

  static final class BranchController extends CrudController<Change, View, String> {
    BranchController(Branches service) {
      super(service, View::id);
    }
  }

  private SessionFactory factory;

  @BeforeEach
  void start() {
    factory =
        new Configuration()
            .addAnnotatedClass(Branch.class)
            .addAnnotatedClass(Unversioned.class)
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:crud-" + UUID.randomUUID())
            .setProperty("hibernate.hbm2ddl.auto", "create-drop")
            .setProperty("hibernate.show_sql", "false")
            .buildSessionFactory();
  }

  @AfterEach
  void close() {
    factory.close();
  }

  @Test
  void onlyOwnedRowsAreVisibleAndEditableAndRequestsContainOnlyAllowedFields() {
    try (var em = factory.createEntityManager()) {
      em.getTransaction().begin();
      var alice = new Branches(em, "alice");
      var bob = new Branches(em, "bob");
      View mine = alice.create(new Change("Lima", null));
      View theirs = bob.create(new Change("Arequipa", null));
      assertEquals("alice", em.find(Branch.class, mine.id()).owner);
      assertThat(Change.class.getRecordComponents())
          .extracting("name")
          .containsExactly("name", "version");
      assertFalse(alice.find(theirs.id()).isPresent());
      assertThrows(NotFoundException.class, () -> alice.get(theirs.id()));
      assertThrows(
          NotFoundException.class,
          () -> alice.update(theirs.id(), new Change("stolen", theirs.version())));
      assertThrows(
          NotFoundException.class,
          () -> alice.delete(theirs.id(), String.valueOf(theirs.version())));
      assertEquals(List.of(mine), alice.list(PageRequest.of(0, 10)).content());
      assertThrows(
          ValidationException.class,
          () -> alice.list(new PageRequest(0, 10, Sort.parse(List.of("owner")))));
      assertThrows(AccessException.class, () -> new Branches(em, "").create(new Change("x", null)));
      View updated = alice.update(mine.id(), new Change("Lima Centro", mine.version()));
      assertThrows(
          ValidationException.class,
          () -> alice.update(mine.id(), new Change("missing version", null)));
      assertEquals(mine.version() + 1, updated.version());
      var stale =
          assertThrows(
              BusinessException.class,
              () -> alice.update(mine.id(), new Change("lost update", mine.version())));
      assertEquals(CoreErrors.CONFLICT.code(), stale.code());
      var staleDelete =
          assertThrows(
              BusinessException.class,
              () -> alice.delete(mine.id(), String.valueOf(mine.version())));
      assertEquals(CoreErrors.PRECONDITION_FAILED.code(), staleDelete.code());
      assertTrue(alice.find(mine.id()).isPresent());
      assertThrows(ValidationException.class, () -> alice.delete(mine.id(), ""));
      assertThrows(ValidationException.class, () -> alice.delete(mine.id(), null));
      var controller = new BranchController(alice);
      for (String invalid : List.of("*", "W/\"1\"", "1", "\"1\",\"2\"", "\"bad\nversion\""))
        assertThrows(ValidationException.class, () -> controller.delete(mine.id(), invalid));
      alice.delete(mine.id(), String.valueOf(alice.get(mine.id()).version()));
      assertTrue(alice.find(mine.id()).isEmpty());
      em.getTransaction().commit();
    }
  }

  @Test
  void concurrentTransactionsCannotOverwriteTheCommittedVersion() {
    View created;
    try (var em = factory.createEntityManager()) {
      em.getTransaction().begin();
      created = new Branches(em, "alice").create(new Change("original", null));
      em.getTransaction().commit();
    }
    try (var first = factory.createEntityManager();
        var second = factory.createEntityManager()) {
      first.getTransaction().begin();
      second.getTransaction().begin();
      var a = new Branches(first, "alice");
      var b = new Branches(second, "alice");
      b.get(created.id()); // retains the old managed version before the other commit
      a.update(created.id(), new Change("winner", created.version()));
      first.getTransaction().commit();
      assertThrows(
          RuntimeException.class,
          () -> b.update(created.id(), new Change("loser", created.version())));
      second.getTransaction().rollback();
    }
    try (var em = factory.createEntityManager()) {
      assertEquals("winner", em.find(Branch.class, created.id()).name);
    }
  }

  @Test
  void unversionedEntitiesAreRejectedDuringConstruction() {
    try (var em = factory.createEntityManager()) {
      var repo = new SimpleJpaRepository<Unversioned, String>(Unversioned.class, em);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new JpaCrudService<Unversioned, Change, View, String>(
                  Unversioned.class, repo, repo, em, "branch") {
                protected Unversioned newEntity(Change request) {
                  throw new AssertionError();
                }

                protected void apply(Unversioned entity, Change changes) {
                  throw new AssertionError();
                }

                protected View toResponse(Unversioned entity) {
                  throw new AssertionError();
                }

                protected Object expectedVersion(Change request) {
                  throw new AssertionError();
                }

                protected void authorize(CrudAction action, @Nullable Unversioned entity) {
                  throw new AssertionError();
                }

                protected Specification<Unversioned> listScope() {
                  throw new AssertionError();
                }

                protected Set<String> sortableFields() {
                  throw new AssertionError();
                }
              });
    }
  }

  @Test
  void controllerUsesSeparateRepresentationsAndReturnsGeneratedLocation() {
    try (var em = factory.createEntityManager()) {
      em.getTransaction().begin();
      var controller = new BranchController(new Branches(em, "alice"));
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest("POST", "/branches")));
      try {
        var response = controller.create(new Change("Lima", null));
        assertEquals(201, response.getStatusCode().value());
        View created = response.getBody();
        assertThat(response.getHeaders().getLocation())
            .hasToString("http://localhost/branches/" + created.id());
        assertEquals(created, controller.get(created.id()));
        assertEquals(
            "New", controller.update(created.id(), new Change("New", created.version())).name());
        assertEquals(
            204,
            controller
                .delete(created.id(), "\"" + controller.get(created.id()).version() + "\"")
                .getStatusCode()
                .value());
      } finally {
        RequestContextHolder.resetRequestAttributes();
      }
      em.getTransaction().commit();
    }
  }
}
