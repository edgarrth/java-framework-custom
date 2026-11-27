package pe.axiz.core.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Audita un método: actor, acción, recurso, resultado y argumentos y resultado (saneados). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

  /** Nombre de la acción, por ejemplo {@code order.create}. */
  String action();

  /** Tipo de recurso, por ejemplo {@code order}. */
  String resource();

  /** Nombre del argumento que contiene el id del recurso, si existe. */
  String resourceIdArgument() default "";

  boolean captureArguments() default true;

  boolean captureResult() default false;

  AuditPolicy policy() default AuditPolicy.ALWAYS;
}
