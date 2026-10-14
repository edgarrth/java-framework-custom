package pe.axiz.spring.foundation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint for any registered identifier type, e.g. {@code @ValidIdentifier(type
 * = "ruc") String taxId}. Null values are valid (combine with {@code @NotNull}).
 */
@Documented
@Constraint(validatedBy = IdentifierConstraintValidator.class)
@Target({
  ElementType.FIELD,
  ElementType.PARAMETER,
  ElementType.RECORD_COMPONENT,
  ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidIdentifier {

  String type();

  String message() default "{axiz.identifier.invalid}";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
