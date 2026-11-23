package pe.axiz.core.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registra en log la entrada y salida de un método (o de cada método público de una clase) con
 * duración, resultado y argumentos y resultado enmascarados. Lo aplica el starter de logging
 * mediante un proxy de Spring.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LogOperation {

  /** Nombre de la operación; por defecto {@code Class.method}. */
  String value() default "";

  boolean logArguments() default false;

  boolean logResult() default false;

  /** Nivel de log para la entrada y la salida exitosa (los fallos se registran en WARN o ERROR). */
  Level level() default Level.INFO;

  /** Niveles de log. */
  enum Level {
    DEBUG,
    INFO
  }
}
