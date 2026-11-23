package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Sustituye a {@code @Component} de Spring: la regla toma nombres de anotaciones. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Component {
  String value() default "";
}
