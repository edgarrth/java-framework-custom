package pe.axiz.framework.cqrs.spring;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.framework.cqrs.Invocation;
import pe.axiz.framework.cqrs.PipelineBehavior;

/** Bean Validation of commands and queries before anything else runs (400 on violations). */
public class ValidationBehavior implements PipelineBehavior {

  private final Validator validator;

  public ValidationBehavior(Validator validator) {
    this.validator = Objects.requireNonNull(validator);
  }

  @Override
  public int order() {
    return VALIDATION;
  }

  @Override
  public <R> R handle(Invocation<R> invocation, Next<R> next) {
    validate(validator, invocation.request());
    return next.proceed();
  }

  /** Throws {@link ValidationException} with every violation of the request. */
  static void validate(Validator validator, Object request) {
    Set<ConstraintViolation<Object>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
      List<FieldViolation> fields =
          violations.stream()
              .map(
                  v ->
                      FieldViolation.of(
                          v.getPropertyPath().toString(),
                          v.getConstraintDescriptor()
                              .getAnnotation()
                              .annotationType()
                              .getSimpleName(),
                          v.getMessage()))
              .toList();
      throw new ValidationException(fields);
    }
  }
}
