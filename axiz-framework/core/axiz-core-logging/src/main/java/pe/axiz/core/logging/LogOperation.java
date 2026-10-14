package pe.axiz.core.logging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Logs entry and exit of a method (or of every public method of a class) with duration, outcome and
 * masked arguments and result. Applied by the logging starter through a Spring proxy.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LogOperation {

  /** Operation name; defaults to {@code Class.method}. */
  String value() default "";

  boolean logArguments() default true;

  boolean logResult() default false;

  /** Log level for entry and successful exit (failures are logged at WARN or ERROR). */
  Level level() default Level.INFO;

  /** Log levels. */
  enum Level {
    DEBUG,
    INFO
  }
}
