package pe.axiz.core.sensitive;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field, record component or getter as sensitive. The masking policy decides, per output
 * channel, whether the value is shown, masked with {@link #mask()} or removed.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.FIELD,
  ElementType.RECORD_COMPONENT,
  ElementType.METHOD,
  ElementType.PARAMETER
})
public @interface Sensitive {

  SensitivityLevel level() default SensitivityLevel.HIGH;

  /** Mask to use where the value may be partially shown. */
  MaskType mask() default MaskType.PARTIAL;
}
