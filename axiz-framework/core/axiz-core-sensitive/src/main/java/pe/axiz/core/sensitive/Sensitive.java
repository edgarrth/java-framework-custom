package pe.axiz.core.sensitive;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un campo, componente de record o getter como sensible. La política de enmascaramiento
 * decide, por canal de salida, si el valor se muestra, se enmascara con {@link #mask()} o se
 * elimina.
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

  /** Máscara a usar donde el valor puede mostrarse en parte. */
  MaskType mask() default MaskType.PARTIAL;
}
