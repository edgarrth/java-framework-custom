package pe.axiz.core.errors;

import java.util.List;
import java.util.Map;

/** La entrada no es válida; lleva una violación por campo. */
public class ValidationException extends AxizException {

  private static final long serialVersionUID = 1L;

  public ValidationException(List<FieldViolation> violations) {
    this(CoreErrors.VALIDATION_FAILED, violations);
  }

  public ValidationException(ErrorDefinition definition, List<FieldViolation> violations) {
    super(
        definition,
        "Validation failed: " + violations.size() + " violation(s)",
        Map.of(),
        violations,
        null,
        null);
  }
}
