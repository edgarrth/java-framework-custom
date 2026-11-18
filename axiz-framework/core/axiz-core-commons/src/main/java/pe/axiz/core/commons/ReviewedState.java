package pe.axiz.core.commons;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Records review of shared mutable state; it does not prove immutability or thread safety. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface ReviewedState {
  /** Concrete reason: immutable copy, synchronization strategy or bounded concurrent cache. */
  String reason();
}
