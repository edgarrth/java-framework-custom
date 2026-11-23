package pe.axiz.core.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marca un endpoint no idempotente (POST/PATCH) como que requiere un {@code Idempotency-Key}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Idempotent {

  /** Indica si las peticiones sin la cabecera se rechazan (400) o se procesan sin protección. */
  boolean required() default true;
}
