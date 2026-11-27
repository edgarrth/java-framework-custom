package pe.axiz.structure.layered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.NotFoundException;

class QueryServiceTest {
  @Test
  void getReturnsOnlyTheAuthorizedLookupOrThePublicNotFoundError() {
    QueryService<String, String> query =
        new QueryService<>() {
          @Override
          public String resourceName() {
            return "parameter";
          }

          @Override
          public Optional<String> find(String id) {
            return "visible".equals(id) ? Optional.of("authorized-value") : Optional.empty();
          }

          @Override
          public Page<String> list(PageRequest request) {
            return Page.empty(request);
          }
        };
    assertEquals("authorized-value", query.get("visible"));
    var denied = assertThrows(NotFoundException.class, () -> query.get("foreign"));
    assertEquals(404, denied.category().httpStatus());
    assertThrows(NotFoundException.class, () -> query.get("absent"));
  }
}
