package pe.axiz.framework.hexagonal.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a use case implementation. The framework wraps its public methods in a transaction, an
 * observation with the operation name and the domain event lifecycle.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UseCase {

  /** Operation name, e.g. {@code create-payment}; defaults to the class name in kebab-case. */
  String value() default "";

  /** Read-only use cases run in read-only transactions (routed to Aurora readers). */
  boolean readOnly() default false;
}
