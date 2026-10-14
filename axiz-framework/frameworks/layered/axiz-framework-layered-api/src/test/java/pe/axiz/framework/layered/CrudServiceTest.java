package pe.axiz.framework.layered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    service.create(new Parameter("b", "2"));
    service.create(new Parameter("a", "1"));
    service.create(new Parameter("c", "3"));
    assertEquals("a", service.list(PageRequest.of(0, 2)).content().get(0).code());
    assertEquals(3, service.list(PageRequest.of(0, 2)).metadata().totalElements());
    assertEquals("9", service.update("a", new Parameter("ignored", "9")).value());
    service.delete("b");
    assertThrows(NotFoundException.class, () -> service.get("b"));
    assertThrows(NotFoundException.class, () -> service.delete("zz"));
  }
}
