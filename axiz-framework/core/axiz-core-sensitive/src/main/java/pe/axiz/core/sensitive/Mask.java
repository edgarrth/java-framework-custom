package pe.axiz.core.sensitive;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Shorthand for a masked value of {@link SensitivityLevel#MEDIUM} with the given mask type. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.FIELD,
  ElementType.RECORD_COMPONENT,
  ElementType.METHOD,
  ElementType.PARAMETER
})
public @interface Mask {

  MaskType value();
}
