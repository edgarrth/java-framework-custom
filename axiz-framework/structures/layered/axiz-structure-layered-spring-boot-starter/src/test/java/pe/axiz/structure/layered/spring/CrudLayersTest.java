package pe.axiz.structure.layered.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.commons.PageResponse;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.structure.layered.InMemoryCrudService;

/** The JPA service and the REST controller of a layered CRUD resource. */
class CrudLayersTest {

  /** A mutable entity, as JPA entities are. */
  static final class Branch {
    final String code;
    String name;

    Branch(String code, String name) {
      this.code = code;
      this.name = name;
    }
  }

  /** A repository over a map: only the methods the service uses. */
  @SuppressWarnings("unchecked")
  private static JpaRepository<Branch, String> repository(Map<String, Branch> rows) {
    return (JpaRepository<Branch, String>)
        Proxy.newProxyInstance(
            JpaRepository.class.getClassLoader(),
            new Class<?>[] {JpaRepository.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "findById" -> Optional.ofNullable(rows.get((String) args[0]));
                  case "findAll" -> {
                    Pageable pageable = (Pageable) args[0];
                    List<Branch> all = List.copyOf(rows.values());
                    yield new PageImpl<>(all, pageable, all.size());
                  }
                  case "save" -> {
                    Branch b = (Branch) args[0];
                    rows.put(b.code, b);
                    yield b;
                  }
                  case "delete" -> {
                    rows.remove(((Branch) args[0]).code);
                    yield null;
                  }
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  static final class Branches extends JpaCrudService<Branch, String> {
    Branches(JpaRepository<Branch, String> repository) {
      super(repository, "branch");
    }

    @Override
    protected void apply(Branch current, Branch changes) {
      current.name = changes.name;
    }
  }

  @Test
  void theJpaServiceReadsWritesAndNamesWhatItCannotFind() {
    Map<String, Branch> rows = new ConcurrentHashMap<>();
    Branches branches = new Branches(repository(rows));
    assertEquals("branch", branches.resourceName());

    branches.create(new Branch("LIM-01", "Lima Centro"));
    assertEquals("Lima Centro", branches.find("LIM-01").orElseThrow().name);
    Page<Branch> page = branches.list(PageRequest.of(0, 10));
    assertEquals(1, page.content().size());

    Branch renamed = branches.update("LIM-01", new Branch("ignored", "Lima Cercado"));
    assertEquals("LIM-01", renamed.code, "the identity of the stored row is kept");
    assertEquals("Lima Cercado", rows.get("LIM-01").name);

    branches.delete("LIM-01");
    assertThat(rows).isEmpty();
    assertThrows(NotFoundException.class, () -> branches.update("LIM-01", renamed));
    assertThrows(NotFoundException.class, () -> branches.delete("LIM-01"));
  }

  record Agency(String code, String name) {}

  static final class Agencies extends CrudController<Agency, String> {
    Agencies(InMemoryCrudService<Agency, String> service) {
      super(service, Agency::code);
    }
  }

  @Test
  void theControllerAnswersWithTheRestConventions() {
    InMemoryCrudService<Agency, String> service =
        new InMemoryCrudService<>("agency", Agency::code, (id, a) -> new Agency(id, a.name()));
    Agencies controller = new Agencies(service);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agencies");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      ResponseEntity<Agency> created = controller.create(new Agency("A-1", "Miraflores"));
      assertEquals(201, created.getStatusCode().value());
      assertThat(created.getHeaders().getLocation()).hasToString("http://localhost/agencies/A-1");
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    assertEquals("Miraflores", controller.get("A-1").name());
    PageResponse<Agency> list = controller.list(PageRequest.of(0, 20));
    assertThat(list.items()).extracting(Agency::code).containsExactly("A-1");
    assertEquals("San Isidro", controller.update("A-1", new Agency("x", "San Isidro")).name());
    assertEquals(204, controller.delete("A-1").getStatusCode().value());
    assertThrows(NotFoundException.class, () -> controller.get("A-1"));
  }
}
