package pe.axiz.core.commons;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class written by a generator, not by a person (the models openapi-generator produces from
 * the contract of a service, see axiz-parent-service).
 *
 * <p>It is kept in the class files so coverage tools tell generated code from the code of the
 * service: JaCoCo leaves out of its counters every class annotated with an annotation of class or
 * runtime retention whose simple name contains {@code Generated}. The coverage gates then measure
 * the code the service's tests are meant to exercise; the generated models are covered by the
 * contract (their format) and by the generator's own tests. {@code jakarta.annotation.Generated},
 * which the generator also writes, has source retention and does not reach the class files.
 *
 * <p>Never put it on hand-written code: that would take it out of the coverage gates.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Generated {

  /**
   * The generator that wrote the class.
   *
   * @return its name, such as {@code openapi-generator}
   */
  String value();
}
