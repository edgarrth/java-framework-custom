package pe.axiz.core.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a non-idempotent endpoint (POST/PATCH) as requiring an {@code Idempotency-Key}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Idempotent {

  /** Whether requests without the header are rejected (400) or processed without protection. */
  boolean required() default true;
}
