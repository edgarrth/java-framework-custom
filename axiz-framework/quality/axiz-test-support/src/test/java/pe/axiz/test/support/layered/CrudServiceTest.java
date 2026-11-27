package pe.axiz.test.support.layered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.structure.layered.CrudService;

class CrudServiceTest {
  @Test
  void updateRejectsMissingVersionsBeforeMutating() {
    for (String missing : List.of("", " ")) {
      var fixture =
          new InMemoryCrudService<Parameter, String>(
              "parameter",
              Parameter::code,
              (id, value) -> value,
              value -> value.version() < 0 ? missing : Long.toString(value.version()));
      var original = fixture.create(new Parameter("key", "original", 0));
      assertThrows(
          ValidationException.class,
          () -> fixture.update("key", new Parameter("key", "invalid", -1)));
      assertSame(original, fixture.get("key"));
    }
  }

  record Parameter(String code, String value, long version) {
    Parameter(String code, String value) {
      this(code, value, 0);
    }
  }

  private final CrudService<Parameter, Parameter, String> service =
      new InMemoryCrudService<>(
          "parameter",
          Parameter::code,
          (id, p) -> new Parameter(id, p.value(), p.version() + 1),
          p -> Long.toString(p.version()));

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
    assertEquals(new Parameter("a", "9", 1), updated, "the id of the path wins");
    assertEquals("9", service.get("a").value(), "the change is stored");

    var conflict =
        assertThrows(
            BusinessException.class, () -> service.update("a", new Parameter("a", "stale", 0)));
    assertEquals(CoreErrors.CONFLICT, conflict.definition());
    assertEquals(updated, service.get("a"), "a stale update leaves the stored value intact");
    var current = service.update("a", new Parameter("a", "current", 1));
    assertEquals(new Parameter("a", "current", 2), current);

    assertThrows(BusinessException.class, () -> service.delete("a", "0"));
    assertThrows(ValidationException.class, () -> service.delete("a", ""));
    assertThrows(ValidationException.class, () -> service.delete("a", null));
    assertEquals("current", service.get("a").value());
    service.delete("b", "0");
    assertTrue(service.find("b").isEmpty());
    NotFoundException missing = assertThrows(NotFoundException.class, () -> service.get("b"));
    assertTrue(missing.getMessage().contains("parameter"), missing.getMessage());
    assertThrows(NotFoundException.class, () -> service.delete("zz", "0"));
    assertThrows(NotFoundException.class, () -> service.update("zz", new Parameter("zz", "0")));
  }
}
