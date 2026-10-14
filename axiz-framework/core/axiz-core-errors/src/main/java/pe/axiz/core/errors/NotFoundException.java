package pe.axiz.core.errors;

import java.util.List;
import java.util.Map;

/** The requested resource does not exist. */
public class NotFoundException extends AxizException {

  private static final long serialVersionUID = 1L;

  public NotFoundException(String resource, Object id) {
    this(CoreErrors.NOT_FOUND, resource, id);
  }

  public NotFoundException(ErrorDefinition definition, String resource, Object id) {
    super(
        definition,
        resource + " not found",
        Map.of("resource", resource, "id", String.valueOf(id)),
        List.of(),
        null,
        null);
  }
}
