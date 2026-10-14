package pe.axiz.core.metrics;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Counts and times a method as an event. Outcome and reason are derived from the exception;
 * arguments and the result that implement {@link MetricSource} contribute amount, value and tags.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Metered {

  /** Event name, e.g. {@code payment}. */
  String event();

  /** Optional category; the catalog definition wins when present. */
  String category() default "";

  /** Whether to record the duration timer in addition to the counter. */
  boolean duration() default true;
}
