package pe.axiz.spring.foundation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.identifiers.IdentifierRegistry;

/** Validador de {@link ValidIdentifier} respaldado por el registro de identificadores. */
public final class IdentifierConstraintValidator
    implements ConstraintValidator<ValidIdentifier, CharSequence> {

  private String type = "";
  private final IdentifierRegistry registry;

  public IdentifierConstraintValidator(IdentifierRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  @Override
  public void initialize(ValidIdentifier annotation) {
    this.type = annotation.type();
  }

  @Override
  public boolean isValid(@Nullable CharSequence value, ConstraintValidatorContext context) {
    return value == null || registry.isValid(type, value.toString());
  }
}
