package pe.axiz.structure.hexagonal.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca una implementación de caso de uso. El framework envuelve sus métodos públicos en una
 * transacción, una observación con el nombre de la operación y el ciclo de vida de los eventos de
 * dominio.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UseCase {

  /**
   * Nombre de la operación, por ejemplo {@code create-payment}; por defecto, el nombre de la clase
   * en kebab-case.
   */
  String value() default "";

  /**
   * Los casos de uso de solo lectura se ejecutan en transacciones de solo lectura (encaminadas a
   * los lectores de Aurora).
   */
  boolean readOnly() default false;
}
