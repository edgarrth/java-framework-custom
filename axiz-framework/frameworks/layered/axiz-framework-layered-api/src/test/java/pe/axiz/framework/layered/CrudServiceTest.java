package pe.axiz.framework.layered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.errors.NotFoundException;

class CrudServiceTest {

  record Parameter(String code, String value) {}

  private final CrudService<Parameter, String> service =
      new InMemoryCrudService<>(
          "parameter", Parameter::code, (id, p) -> new Parameter(id, p.value()));

  @Test
  void crudWithPagingAndNotFound() {
    Parameter b = new Parameter("b", "2");
    assertSame(b, service.create(b), "create returns what it stored");
    service.create(new Parameter("a", "1"));
    service.create(new Parameter("c", "3"));
    assertEquals("parameter", service.resourceName());
    assertEquals("1", service.get("a").value());
    assertEquals("a", service.list(PageRequest.of(0, 2)).content().get(0).code());
    assertEquals(3, service.list(PageRequest.of(0, 2)).metadata().totalElements());
    assertEquals(
        List.of("c"),
        service.list(PageRequest.of(1, 2)).content().stream().map(Parameter::code).toList(),
        "the second page starts after the first one");
    assertTrue(service.list(PageRequest.of(5, 2)).content().isEmpty(), "past the end");

    Parameter updated = service.update("a", new Parameter("ignored", "9"));
    assertEquals(new Parameter("a", "9"), updated, "the id of the path wins");
    assertEquals("9", service.get("a").value(), "the change is stored");

    service.delete("b");
    assertTrue(service.find("b").isEmpty());
    NotFoundException missing = assertThrows(NotFoundException.class, () -> service.get("b"));
    assertTrue(missing.getMessage().contains("parameter"), missing.getMessage());
    assertThrows(NotFoundException.class, () -> service.delete("zz"));
    assertThrows(NotFoundException.class, () -> service.update("zz", new Parameter("zz", "0")));
  }
}
