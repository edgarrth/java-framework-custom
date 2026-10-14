package pe.axiz.spring.foundation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

/** Validator of {@link ValidIdentifier} backed by the identifier registry. */
public class IdentifierConstraintValidator
    implements ConstraintValidator<ValidIdentifier, CharSequence> {

  private String type = "";

  @Override
  public void initialize(ValidIdentifier annotation) {
    this.type = annotation.type();
  }

  @Override
  public boolean isValid(@Nullable CharSequence value, ConstraintValidatorContext context) {
    return value == null || IdentifierValidation.registry().isValid(type, value.toString());
  }
}
