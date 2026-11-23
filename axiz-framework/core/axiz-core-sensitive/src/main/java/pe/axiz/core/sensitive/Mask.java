package pe.axiz.core.sensitive;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Atajo para un valor enmascarado de {@link SensitivityLevel#MEDIUM} con el tipo de máscara
 * indicado.
 */
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
